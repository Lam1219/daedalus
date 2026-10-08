package com.medicina.daedaluscompanion.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.medicina.daedaluscompanion.model.EcgSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ChestStrapBLEManager"

/**
 * BLE manager for connecting to the Cycplus H2 Heart Rate chest strap.
 *
 * The Cycplus H2 exposes the standard Bluetooth Heart Rate Service (UUID: 0x180D)
 * with the Heart Rate Measurement characteristic (UUID: 0x2A37).
 *
 * Parses the BLE Heart Rate Measurement format per Bluetooth SIG spec:
 * - Byte 0: Flags (bit 0 = RR interval present)
 * - Byte 1-2: Heart Rate (UINT8 or UINT16)
 * - Byte 3+: RR Interval (UINT16, in 1/1024 second units)
 */
class ChestStrapBLEManager(private val context: Context) {

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDeviceAddress: String? = null

    // RR interval and HR data flow
    private val _ecgSamples = MutableStateFlow<List<EcgSample>>(emptyList())
    val ecgSamples: StateFlow<List<EcgSample>> = _ecgSamples.asStateFlow()

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val accumulatedSamples = mutableListOf<EcgSample>()

    /** Scan for and connect to the Cycplus H2 chest strap */
    fun scanAndConnect() {
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled")
            return
        }

        _connectionState.value = ConnectionState.SCANNING
        Log.i(TAG, "Scanning for heart rate devices...")

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "Bluetooth LE Scanner not available")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name

                // Look for heart rate devices (Cycplus H2 may appear as "Cycplus", "H2", or generic)
                if (name != null && (name.contains("Cycplus", ignoreCase = true) ||
                            name.contains("H2", ignoreCase = true) ||
                            name.contains("Heart", ignoreCase = true) ||
                            name.contains("Polar", ignoreCase = true))) {
                    scanner.stopScan(this)
                    _connectionState.value = ConnectionState.CONNECTING
                    connectToDevice(device.address)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                _connectionState.value = ConnectionState.ERROR
            }
        }

        scanner.startScan(scanCallback)

        // Auto-stop scan after 10 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                scanner.stopScan(scanCallback)
            } catch (_: Exception) {}
        }, 10000)
    }

    /** Connect to a specific device by MAC address */
    private fun connectToDevice(address: String) {
        val device = bluetoothAdapter.getRemoteDevice(address)
        connectedDeviceAddress = address

        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    /** Disconnect from the current device */
    fun disconnect() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDeviceAddress = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Clear accumulated RR interval samples */
    fun clearSamples() {
        accumulatedSamples.clear()
        _ecgSamples.value = emptyList()
    }

    /** Get accumulated samples for export */
    fun getSamples(): List<EcgSample> = accumulatedSamples.toList()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to chest strap")
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from chest strap")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Find Heart Rate Service (0x180D)
                val hrService = gatt.getService(HEART_RATE_SERVICE_UUID)
                if (hrService != null) {
                    val hrCharacteristic = hrService.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
                    if (hrCharacteristic != null) {
                        gatt.setCharacteristicNotification(hrCharacteristic, true)

                        // Enable notification descriptor
                        val descriptor = hrCharacteristic.getDescriptor(
                            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        Log.i(TAG, "HR notifications enabled")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                parseHeartRateMeasurement(characteristic.value)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Descriptor write successful")
            }
        }
    }

    /**
     * Parse BLE Heart Rate Measurement characteristic value.
     * Format per Bluetooth SIG Heart Rate Profile:
     * - Flags (1 byte): bit 0 = RR interval present, bit 1 = sensor contact, etc.
     * - Heart Rate Value (1 or 2 bytes): UINT8 if bit 0 of flags = 0, UINT16 if bit 0 = 1
     * - RR Interval (2 bytes each): present if bit 0 of flags = 1, units = 1/1024 seconds
     */
    private fun parseHeartRateMeasurement(value: ByteArray) {
        if (value.isEmpty()) return

        val flags = value[0].toInt()
        val rrPresent = (flags and 0x01) == 1
        val hrFormatUint16 = (flags and 0x02) == 1

        var offset = 1

        // Parse heart rate
        val hrBpm = if (hrFormatUint16) {
            if (offset + 1 >= value.size) return
            val hr = (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            hr.toFloat()
        } else {
            if (offset >= value.size) return
            val hr = value[offset].toInt() and 0xFF
            offset += 1
            hr.toFloat()
        }

        // Skip energy expended if present (bit 3)
        if ((flags and 0x08) != 0) {
            offset += 2
        }

        // Skip sensor contact if present (bit 4)
        if ((flags and 0x10) != 0) {
            offset += 1
        }

        // Parse RR intervals
        if (rrPresent) {
            while (offset + 1 < value.size) {
                val rrRaw = (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                val rrMs = rrRaw / 1024f  // Convert from 1/1024s to ms

                if (rrMs > 0) {
                    val sample = EcgSample(
                        epochMs = System.currentTimeMillis(),
                        rrMs = rrMs,
                        hrBpm = hrBpm,
                        beatQuality = 1f,
                    )
                    accumulatedSamples.add(sample)
                    _ecgSamples.value = accumulatedSamples.toList()
                }
            }
        } else {
            // No RR interval — just log HR
            val sample = EcgSample(
                epochMs = System.currentTimeMillis(),
                rrMs = null,
                hrBpm = hrBpm,
                beatQuality = 0.5f,  // Lower confidence without RR
            )
            accumulatedSamples.add(sample)
            _ecgSamples.value = accumulatedSamples.toList()
        }
    }

    companion object {
        private val HEART_RATE_SERVICE_UUID = java.util.UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_UUID = java.util.UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    }
}

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR
}
