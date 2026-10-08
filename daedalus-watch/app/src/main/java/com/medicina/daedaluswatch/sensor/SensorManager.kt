package com.medicina.daedaluswatch.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.util.Log
import com.medicina.daedaluswatch.model.ImuSample
import com.medicina.daedaluswatch.model.HrSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "SensorManager"

/**
 * Manages access to watch sensors via Android's SensorManager.
 * Collects: heart rate from the watch's heart rate sensor.
 */
class SensorManager(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    var isHeartRateAvailable: Boolean = heartRateSensor != null

    /**
     * Flow of heart rate samples.
     * Emits HrSample with epoch_ms, hr_bpm.
     */
    fun heartRateFlow(): Flow<HrSample> = callbackFlow {
        if (heartRateSensor == null) {
            Log.w(TAG, "Heart rate sensor not available")
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
                    val hrBpm = event.values[0]
                    if (hrBpm > 0) {
                        val sample = HrSample(
                            epochMs = System.currentTimeMillis(),
                            hrBpm = hrBpm,
                            source = "watch_sensor",
                        )
                        trySend(sample)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, heartRateSensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    /** HRV not available via standard Android sensors */
    fun isHrvAvailable(): Boolean = false
}

/**
 * Raw IMU sensor manager using Android's SensorManager (for high-frequency IMU at 50Hz).
 */
class ImuSensorManager(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    var isAvailable: Boolean = accelerometer != null && gyroscope != null

    /**
     * Register a listener for IMU samples at 50Hz.
     * Returns a function to unregister.
     */
    fun registerListener(
        samplingPeriodUs: Int = 20000,  // 50 Hz
        onSample: (ImuSample) -> Unit,
    ): () -> Unit {
        if (!isAvailable) {
            Log.w(TAG, "IMU sensors not available")
            return {}
        }

        val listener = object : SensorEventListener {
            private var lastAccel = floatArrayOf(0f, 0f, 0f)
            private var lastGyro = floatArrayOf(0f, 0f, 0f)
            private var lastAccelTime = 0L
            private var lastGyroTime = 0L

            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccel[0] = event.values[0]
                        lastAccel[1] = event.values[1]
                        lastAccel[2] = event.values[2]
                        lastAccelTime = event.timestamp / 1_000_000  // ns → ms
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        lastGyro[0] = event.values[0]
                        lastGyro[1] = event.values[1]
                        lastGyro[2] = event.values[2]
                        lastGyroTime = event.timestamp / 1_000_000
                    }
                }

                // Emit when we have both accel and gyro
                if (lastAccelTime > 0 && lastGyroTime > 0) {
                    val elapsedMs = kotlin.math.max(lastAccelTime, lastGyroTime)
                    val motionIntensity = kotlin.math.sqrt(
                        lastAccel[0] * lastAccel[0] +
                        lastAccel[1] * lastAccel[1] +
                        lastAccel[2] * lastAccel[2]
                    )
                    val sample = ImuSample(
                        epochMs = System.currentTimeMillis(),
                        elapsedMs = elapsedMs,
                        ax = lastAccel[0],
                        ay = lastAccel[1],
                        az = lastAccel[2],
                        gx = lastGyro[0],
                        gy = lastGyro[1],
                        gz = lastGyro[2],
                        motionIntensity = motionIntensity,
                    )
                    onSample(sample)
                    // Reset to avoid duplicate emissions
                    lastAccelTime = 0L
                    lastGyroTime = 0L
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, accelerometer, samplingPeriodUs)
        sensorManager.registerListener(listener, gyroscope, samplingPeriodUs)

        return {
            sensorManager.unregisterListener(listener)
        }
    }
}
