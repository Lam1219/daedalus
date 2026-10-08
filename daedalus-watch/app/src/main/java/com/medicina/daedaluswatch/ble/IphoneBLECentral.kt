package com.medicina.daedaluswatch.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.medicina.daedaluswatch.model.ImuSample
import com.medicina.daedaluswatch.model.HrSample

private const val TAG = "IphoneBLECentral"

object DaedalusIPhoneService {
    val SERVICE_UUID = java.util.UUID.fromString("A0000000-0000-0000-0000-000000000001")
    val IMU_CHARACTERISTIC_UUID = java.util.UUID.fromString("A0000001-0000-0000-0000-000000000001")
    val HR_CHARACTERISTIC_UUID = java.util.UUID.fromString("A0000002-0000-0000-0000-000000000001")
    val HAPTICS_CHARACTERISTIC_UUID = java.util.UUID.fromString("A0000003-0000-0000-0000-000000000001")
    val EVENT_CHARACTERISTIC_UUID = java.util.UUID.fromString("A0000004-0000-0000-0000-000000000001")
    val CONTROL_CHARACTERISTIC_UUID = java.util.UUID.fromString("A0000005-0000-0000-0000-000000000001")
}

enum class IphoneConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR
}

class IphoneBLECentral(private val context: Context) {

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false

    private var imuCharacteristic: BluetoothGattCharacteristic? = null
    private var hrCharacteristic: BluetoothGattCharacteristic? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var hapticsCharacteristic: BluetoothGattCharacteristic? = null

    var connectionState: IphoneConnectionState = IphoneConnectionState.DISCONNECTED
        private set

    var statusMessage: String = "Initialized"
        private set

    fun connectToIphone() {
        if (!bluetoothAdapter.isEnabled) {
            statusMessage = "Bluetooth not enabled"
            return
        }
        if (isConnected) {
            statusMessage = "Already connected"
            return
        }

        connectionState = IphoneConnectionState.SCANNING
        statusMessage = "Scanning for iPhone..."
        Log.i(TAG, statusMessage)

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            connectionState = IphoneConnectionState.ERROR
            statusMessage = "BLE Scanner not available"
            return
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val serviceUuids = result.scanRecord?.serviceUuids ?: return

                if (serviceUuids.contains(android.os.ParcelUuid(DaedalusIPhoneService.SERVICE_UUID))) {
                    scanner.stopScan(this)
                    connectionState = IphoneConnectionState.CONNECTING
                    statusMessage = "Connecting to iPhone..."
                    connectToDevice(device.address)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                connectionState = IphoneConnectionState.ERROR
                statusMessage = "Scan failed: $errorCode"
            }
        }

        scanner.startScan(scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
            if (!isConnected) {
                connectionState = IphoneConnectionState.DISCONNECTED
                statusMessage = "Scan timeout — no iPhone found"
            }
        }, 15000)
    }

    private fun connectToDevice(address: String) {
        val device = bluetoothAdapter.getRemoteDevice(address)
        Log.i(TAG, "Connecting to $address")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        imuCharacteristic = null
        hrCharacteristic = null
        connectionState = IphoneConnectionState.DISCONNECTED
        statusMessage = "Disconnected"
    }

    fun writeImuSample(sample: ImuSample) {
        if (!isConnected || imuCharacteristic == null) return
        val data = "${sample.epochMs},${sample.elapsedMs},${sample.ax},${sample.ay},${sample.az},${sample.gx},${sample.gy},${sample.gz},${sample.motionIntensity}"
        writeCharacteristic(imuCharacteristic!!, data)
    }

    fun writeHrSample(sample: HrSample) {
        if (!isConnected || hrCharacteristic == null) return
        val rmssd = sample.hrvRmssdMs?.toString() ?: ""
        val quality = sample.hrQuality?.toString() ?: ""
        val data = "${sample.epochMs},${sample.hrBpm},$rmssd,$quality,${sample.source}"
        writeCharacteristic(hrCharacteristic!!, data)
    }

    fun writeEvent(eventName: String, source: String = "watch", notes: String = "") {
        if (!isConnected || eventCharacteristic == null) return
        val data = "$eventName|$source|$notes"
        writeCharacteristic(eventCharacteristic!!, data)
    }

    fun writeControl(command: String, vararg params: String) {
        if (!isConnected || controlCharacteristic == null) return
        val data = listOf(command, *params).joinToString("|")
        writeCharacteristic(controlCharacteristic!!, data)
    }

    fun writeHapticEvent(eventType: String, patternType: String = "") {
        if (!isConnected || hapticsCharacteristic == null) return
        val data = "$eventType|$patternType"
        writeCharacteristic(hapticsCharacteristic!!, data)
    }

    fun isIphoneConnected(): Boolean = isConnected

    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, data: String) {
        characteristic.value = data.toByteArray(Charsets.UTF_8)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        bluetoothGatt?.writeCharacteristic(characteristic)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to iPhone")
                    isConnected = true
                    connectionState = IphoneConnectionState.CONNECTED
                    statusMessage = "Connected to iPhone"
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from iPhone")
                    isConnected = false
                    bluetoothGatt = null
                    connectionState = IphoneConnectionState.DISCONNECTED
                    statusMessage = "Disconnected from iPhone"
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection state error: $status")
                connectionState = IphoneConnectionState.ERROR
                statusMessage = "Connection error: $status"
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(DaedalusIPhoneService.SERVICE_UUID)
                if (service != null) {
                    Log.i(TAG, "Daedalus service found on iPhone")
                    imuCharacteristic = service.getCharacteristic(DaedalusIPhoneService.IMU_CHARACTERISTIC_UUID)
                    hrCharacteristic = service.getCharacteristic(DaedalusIPhoneService.HR_CHARACTERISTIC_UUID)
                    hapticsCharacteristic = service.getCharacteristic(DaedalusIPhoneService.HAPTICS_CHARACTERISTIC_UUID)
                    eventCharacteristic = service.getCharacteristic(DaedalusIPhoneService.EVENT_CHARACTERISTIC_UUID)
                    controlCharacteristic = service.getCharacteristic(DaedalusIPhoneService.CONTROL_CHARACTERISTIC_UUID)
                    statusMessage = "Service discovered — ready to send data"
                } else {
                    Log.e(TAG, "Daedalus service not found on iPhone")
                    statusMessage = "Service not found on iPhone"
                }
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                connectionState = IphoneConnectionState.ERROR
                statusMessage = "Service discovery failed"
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write failed: $status")
            }
        }
    }
}
