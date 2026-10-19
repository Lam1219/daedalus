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
 * Notifications are decoded by [HeartRateMeasurementParser].
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
     * Parse a Heart Rate Measurement notification and record it as [EcgSample]s.
     * See [HeartRateMeasurementParser] for the wire format.
     */
    private fun parseHeartRateMeasurement(value: ByteArray) {
        val measurement = HeartRateMeasurementParser.parse(value) ?: return
        val hrBpm = measurement.hrBpm.toFloat()

        // Parse RR intervals
        if (measurement.rrIntervalsMs.isNotEmpty()) {
            for (rrMs in measurement.rrIntervalsMs) {
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

/** One decoded Heart Rate Measurement (0x2A37) notification. */
data class HeartRateMeasurement(
    val hrBpm: Int,
    /** Sensor contact status (flags bits 1-2): null if the feature is not supported. */
    val sensorContact: Boolean?,
    /** Cumulative energy expended in kJ, or null if not present. */
    val energyExpendedKj: Int?,
    /** RR intervals converted from 1/1024 s to milliseconds, oldest first. */
    val rrIntervalsMs: List<Float>,
)

/**
 * Parser for the Bluetooth SIG Heart Rate Measurement characteristic (0x2A37).
 *
 * Flags byte:
 * - bit 0: HR value format (0 = UINT8, 1 = UINT16)
 * - bits 1-2: sensor contact status (bit 2 = supported, bit 1 = detected)
 * - bit 3: energy expended present (UINT16, kJ)
 * - bit 4: RR intervals present (UINT16 each, 1/1024 s)
 *
 * Fields follow the flags in order: HR value, energy expended, RR intervals. All little-endian.
 */
object HeartRateMeasurementParser {
    private const val FLAG_HR_UINT16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY_EXPENDED = 0x08
    private const val FLAG_RR_INTERVALS = 0x10

    /** Returns null if [value] is too short to hold the flags and HR value. */
    fun parse(value: ByteArray): HeartRateMeasurement? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        var offset = 1

        val hrBpm = if ((flags and FLAG_HR_UINT16) != 0) {
            if (offset + 2 > value.size) return null
            uint16(value, offset).also { offset += 2 }
        } else {
            if (offset + 1 > value.size) return null
            (value[offset].toInt() and 0xFF).also { offset += 1 }
        }

        val sensorContact = if ((flags and FLAG_CONTACT_SUPPORTED) != 0) {
            (flags and FLAG_CONTACT_DETECTED) != 0
        } else {
            null
        }

        var energyExpendedKj: Int? = null
        if ((flags and FLAG_ENERGY_EXPENDED) != 0 && offset + 2 <= value.size) {
            energyExpendedKj = uint16(value, offset)
            offset += 2
        }

        val rrIntervalsMs = mutableListOf<Float>()
        if ((flags and FLAG_RR_INTERVALS) != 0) {
            while (offset + 2 <= value.size) {
                rrIntervalsMs.add(uint16(value, offset) * 1000f / 1024f)
                offset += 2
            }
        }

        return HeartRateMeasurement(hrBpm, sensorContact, energyExpendedKj, rrIntervalsMs)
    }

    private fun uint16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
}

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR
}
