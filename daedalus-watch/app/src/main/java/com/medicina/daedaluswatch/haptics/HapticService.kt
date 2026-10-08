package com.medicina.daedaluswatch.haptics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.medicina.daedaluswatch.R
import com.medicina.daedaluswatch.sync.SessionSyncManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

/**
 * Foreground service that runs haptic cueing AND records sensor data (HR, accel, gyro) to CSV.
 * Uses WakeLock to prevent the CPU from sleeping.
 */
class HapticService : Service() {

    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    private var hapticThread: Thread? = null
    private var currentBpm: Int = 0
    private var currentMode: String = ""

    // Sensor recording
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var csvWriter: BufferedWriter? = null
    private var sessionFile: File? = null
    private var sensorListener: SensorEventListener? = null
    private var sessionSyncManager: SessionSyncManager? = null

    companion object {
        const val EXTRA_BPM = "extra_bpm"
        const val EXTRA_MODE = "extra_mode"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "daedalus_haptic_channel"
    }

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sessionSyncManager = SessionSyncManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val bpm = intent.getIntExtra(EXTRA_BPM, 80)
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "unknown"
                startForegroundService(bpm, mode)
            }
            ACTION_STOP -> {
                stopService()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService(bpm: Int, mode: String) {
        if (isRunning) return
        currentBpm = bpm
        currentMode = mode
        isRunning = true

        // Request battery optimization exemption
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.i("HapticService", "Requesting battery optimization exemption")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Log.i("HapticService", "Already ignoring battery optimizations")
            }
        }

        // Acquire wake lock (1 hour to prevent doze mode)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Daedalus:HapticWakeLock")
            .apply { acquire(60 * 60 * 1000L) }
        Log.i("HapticService", "Wake lock acquired (1 hour)")

        // Start foreground notification
        startForeground(NOTIFICATION_ID, buildNotification(bpm, mode))

        // Create session CSV file
        createSessionFile(mode)

        // Start sensor recording
        startSensorRecording()

        // Start haptic thread
        startHapticThread(bpm)

        // Notify phone
        sessionSyncManager?.notifySessionStart(mode)
    }

    private fun createSessionFile(mode: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "session_${mode}_$timestamp.csv"
        val sessionsDir = File(filesDir, "sessions")
        if (!sessionsDir.exists()) {
            val created = sessionsDir.mkdirs()
            Log.i("HapticService", "Created sessions dir: $created at ${sessionsDir.absolutePath}")
        }
        sessionFile = File(sessionsDir, fileName)

        Log.i("HapticService", "Creating session file: ${sessionFile?.absolutePath}")

        csvWriter = BufferedWriter(FileWriter(sessionFile))
        csvWriter?.write("timestamp_ms,heart_rate_bpm,ax,ay,az,gx,gy,gz\n")
        csvWriter?.flush()
    }

    private fun startSensorRecording() {
        sensorListener = object : SensorEventListener {
            private var lastAccel = floatArrayOf(0f, 0f, 0f)
            private var lastGyro = floatArrayOf(0f, 0f, 0f)
            private var lastWriteTime = 0L

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccel[0] = event.values[0]
                        lastAccel[1] = event.values[1]
                        lastAccel[2] = event.values[2]
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        lastGyro[0] = event.values[0]
                        lastGyro[1] = event.values[1]
                        lastGyro[2] = event.values[2]
                    }
                }

                // Write to CSV at ~10Hz (every 100ms) to avoid excessive I/O
                val now = System.currentTimeMillis()
                if (now - lastWriteTime >= 100) {
                    lastWriteTime = now
                    // Heart rate is collected separately via Health Services
                    val hr = 0 // Will be updated when we integrate HeartRateService
                    val line = "$now,$hr,${lastAccel[0]},${lastAccel[1]},${lastAccel[2]},${lastGyro[0]},${lastGyro[1]},${lastGyro[2]}\n"
                    csvWriter?.write(line)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // Only register IMU sensors (no heart rate permission needed)
        accelerometer?.let { sensorManager?.registerListener(sensorListener, it, 20000) } // 50Hz
        gyroscope?.let { sensorManager?.registerListener(sensorListener, it, 20000) }

        Log.i("HapticService", "Sensor recording started: accel=${accelerometer != null}, gyro=${gyroscope != null}")
    }

    private fun startHapticThread(bpm: Int) {
        val intervalMs = (60000f / bpm).toLong()
        val vib = vibrator ?: return

        Log.i("HapticService", "Starting haptic thread: bpm=$bpm, interval=${intervalMs}ms")

        hapticThread = Thread {
            var consecutiveErrors = 0
            var pulseCount = 0
            try {
                while (isRunning && consecutiveErrors < 10) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vib.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vib.vibrate(50)
                        }
                        consecutiveErrors = 0
                        pulseCount++

                        if (pulseCount % 10 == 0) {
                            Log.i("HapticService", "Pulse #$pulseCount, interval=$intervalMs, isRunning=$isRunning")
                        }

                        var remaining = intervalMs
                        while (remaining > 0 && isRunning) {
                            try {
                                val sleepTime = minOf(remaining, 100L)
                                Thread.sleep(sleepTime)
                                remaining -= sleepTime
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                Log.w("HapticService", "Sleep interrupted, isRunning=$isRunning")
                                if (!isRunning) break
                                // Don't exit on interrupt — keep vibrating
                            }
                        }
                    } catch (e: Exception) {
                        consecutiveErrors++
                        Log.e("HapticService", "Haptic loop error ($consecutiveErrors/10): ${e.message}", e)
                        Thread.sleep(100) // Brief pause before retry
                    }
                }
                Log.i("HapticService", "Haptic thread exiting: isRunning=$isRunning, errors=$consecutiveErrors, pulses=$pulseCount")
            } catch (e: Exception) {
                Log.e("HapticService", "Haptic thread fatal error: ${e.message}", e)
            }
        }.also { 
            it.name = "HapticThread"
            it.isDaemon = false
            it.start() 
            Log.i("HapticService", "Haptic thread started")
        }
    }

    private fun stopService() {
        Log.i("HapticService", "Stopping service. isRunning=$isRunning, sessionFile=${sessionFile?.name}")
        isRunning = false
        hapticThread?.interrupt()
        hapticThread = null
        vibrator?.cancel()

        // Stop sensor recording
        sensorListener?.let { sensorManager?.unregisterListener(it) }
        csvWriter?.flush()
        csvWriter?.close()


        // Send file to phone (synchronously to ensure it completes before service stops)
        sessionFile?.let { file ->
            if (file.exists()) {
                val fileSize = file.length()
                Log.i("HapticService", "Session file exists: ${file.name}, size: $fileSize bytes")
                Log.i("HapticService", "Sending file to phone: ${file.name}")
                kotlinx.coroutines.runBlocking {
                    sessionSyncManager?.transferAndPurgeFile(file, currentMode)
                }
                Log.i("HapticService", "File transfer initiated")
            } else {
                Log.e("HapticService", "Session file does NOT exist: ${file.absolutePath}")
            }
        } ?: Log.e("HapticService", "sessionFile is null")

        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i("HapticService", "Service stopped")
    }

    private fun buildNotification(bpm: Int, mode: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Daedalus — $mode")
            .setContentText("Cueing at $bpm BPM | Recording sensors")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daedalus Haptic Cueing",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopService()
        super.onDestroy()
    }

    /** Get list of session files for syncing */
    fun getSessionFiles(): List<File> {
        val sessionsDir = File(filesDir, "sessions")
        return sessionsDir.listFiles()?.toList() ?: emptyList()
    }
}
