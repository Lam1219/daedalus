package com.medicina.daedaluswatch.haptics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.medicina.daedaluswatch.sensor.VitalsTracker
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
    @Volatile private var intervalMs: Long = 750L

    /** Latest vitals for the session CSV; null = no valid reading yet (written as a blank cell). */
    @Volatile private var latestBreathingRate: Float? = null
    @Volatile private var latestSpo2: Int? = null
    private var vitalsTracker: VitalsTracker? = null
    /** True while an SpO2 reading runs: pulses are skipped so vibration doesn't corrupt the signal. */
    @Volatile private var pulsesPaused = false

    // Sensor recording
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var heartRateSensor: Sensor? = null
    private var csvWriter: BufferedWriter? = null
    private var sessionFile: File? = null
    private var sensorListener: SensorEventListener? = null
    private var sessionSyncManager: SessionSyncManager? = null

    companion object {
        const val EXTRA_BPM = "extra_bpm"
        const val EXTRA_MODE = "extra_mode"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        /** Change the pulse rate of a running cue (rate set on the phone). */
        const val ACTION_SET_BPM = "action_set_bpm"
        /** Pause / resume the pulses of a running cue from the watch screen (recording continues). */
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_RESUME = "action_resume"

        /** Breathing pacer: inhale share of each breath (In 4 s · Out 6 s at 6 breaths/min). */
        const val INHALE_SHARE = 0.4f
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "daedalus_haptic_channel"

        @Volatile
        var isServiceRunning = false
            private set

        /** Mode and rate of the running cue, for the watch screen. */
        @Volatile
        var runningMode: String? = null
            private set
        @Volatile
        var runningBpm: Int = 0
            private set

        /** Name of the session CSV being written right now (null when no cue runs). */
        @Volatile
        var recordingFileName: String? = null
            private set

        /** Live state for the watch screens. */
        @Volatile var startedAtMs: Long = 0L
            private set
        @Volatile var isUserPaused: Boolean = false
            private set
        @Volatile var isMeasuringSpo2: Boolean = false
            private set
        @Volatile var latestHeartRate: Int = 0
            private set
        @Volatile var latestBreathing: Float? = null
            private set
        /** Breathing pacer phase (Breathe mode): inhale or exhale, when it began and how long it lasts. */
        @Volatile var breathInhale: Boolean = true
            private set
        @Volatile var breathPhaseStartMs: Long = 0L
            private set
        @Volatile var breathPhaseMs: Long = 4000L
            private set
    }

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        sessionSyncManager = SessionSyncManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "unknown"
                var bpm = intent.getIntExtra(EXTRA_BPM, 80)
                // Breathe mode is breaths per minute (6–20, as on the phones); an old pulse rate (e.g. 70) falls back to 6.
                if (mode == "rest" && bpm !in 6..20) bpm = 6
                startForegroundService(bpm, mode)
            }
            ACTION_STOP -> {
                stopService()
            }
            ACTION_PAUSE -> if (isRunning) isUserPaused = true
            ACTION_RESUME -> isUserPaused = false
            ACTION_SET_BPM -> {
                val bpm = intent.getIntExtra(EXTRA_BPM, currentBpm)
                val mode = intent.getStringExtra(EXTRA_MODE)
                if (isRunning && bpm > 0 && (mode == null || mode == currentMode)) updateBpm(bpm)
            }
        }
        return START_STICKY
    }

    private fun startForegroundService(bpm: Int, mode: String) {
        if (isRunning) return
        // A stop that is still sending the previous file must not end this new cue.
        stopGeneration++
        currentBpm = bpm
        currentMode = mode
        isRunning = true
        isServiceRunning = true
        runningMode = mode
        runningBpm = bpm
        startedAtMs = System.currentTimeMillis()
        isUserPaused = false

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

        // Acquire wake lock (1 hour to prevent doze mode), releasing one left by the previous cue.
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Daedalus:HapticWakeLock")
            .apply { acquire(60 * 60 * 1000L) }
        Log.i("HapticService", "Wake lock acquired (1 hour)")

        // Start foreground notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(bpm, mode),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(bpm, mode))
        }

        // Create session CSV file
        createSessionFile(mode)

        // Start sensor recording
        startSensorRecording()

        // Breathing rate and SpO2 (Samsung Health Sensor SDK)
        latestBreathingRate = null
        latestBreathing = null
        latestSpo2 = null
        vitalsTracker = VitalsTracker(
            this,
            onBreathingRate = { latestBreathingRate = it; latestBreathing = it },
            onSpo2 = { latestSpo2 = it },
            // The walking cue is the freeze aid: never silence it for a reading.
            canMeasureSpo2 = { currentMode != "walking" },
            onSpo2Measuring = { measuring ->
                pulsesPaused = measuring
                isMeasuringSpo2 = measuring
                Log.i("HapticService", if (measuring) "Pulses paused for SpO2 reading" else "Pulses resumed")
            },
        ).also { it.start() }

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
        recordingFileName = fileName

        Log.i("HapticService", "Creating session file: ${sessionFile?.absolutePath}")

        csvWriter = BufferedWriter(FileWriter(sessionFile))
        // breathing_rate_brpm and spo2_percent are blank until a reading is available.
        csvWriter?.write("timestamp_ms,heart_rate_bpm,ax,ay,az,gx,gy,gz,breathing_rate_brpm,spo2_percent\n")
        csvWriter?.flush()
    }

    private fun startSensorRecording() {
        sensorListener = object : SensorEventListener {
            private var lastAccel = floatArrayOf(0f, 0f, 0f)
            private var lastGyro = floatArrayOf(0f, 0f, 0f)
            private var lastHr = 0f
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
                    Sensor.TYPE_HEART_RATE -> {
                        if (event.values[0] > 0) {
                            lastHr = event.values[0]
                            latestHeartRate = lastHr.toInt()
                        }
                    }
                }

                // Write to CSV at ~10Hz (every 100ms) to avoid excessive I/O
                val now = System.currentTimeMillis()
                if (now - lastWriteTime >= 100) {
                    lastWriteTime = now
                    val breathing = latestBreathingRate?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                    // A SpO2 reading is a single measurement: write it on one row, then clear it.
                    val spo2 = latestSpo2?.toString() ?: ""
                    latestSpo2 = null
                    val line = "$now,$lastHr,${lastAccel[0]},${lastAccel[1]},${lastAccel[2]},${lastGyro[0]},${lastGyro[1]},${lastGyro[2]},$breathing,$spo2\n"
                    csvWriter?.write(line)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let { sensorManager?.registerListener(sensorListener, it, 20000) } // 50Hz
        gyroscope?.let { sensorManager?.registerListener(sensorListener, it, 20000) }

        if (heartRateSensor != null) {
            val granted = checkSelfPermission("android.permission.health.READ_HEART_RATE") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                sensorManager?.registerListener(sensorListener, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                Log.w("HapticService", "Heart rate sensor present but READ_HEART_RATE not granted — recording 0 BPM. Open the watch app once to grant permissions.")
            }
        }

        Log.i("HapticService", "Sensor recording started: accel=${accelerometer != null}, gyro=${gyroscope != null}, heartRate=${heartRateSensor != null}")
    }

    /** Applies a new rate to the running cue without restarting the session recording. */
    private fun updateBpm(bpm: Int) {
        currentBpm = bpm
        runningBpm = bpm
        intervalMs = (60000f / bpm).toLong()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(bpm, currentMode))
        Log.i("HapticService", "Pulse rate changed to $bpm bpm (interval=${intervalMs}ms)")
    }

    private fun startHapticThread(bpm: Int) {
        intervalMs = (60000f / bpm).toLong()
        val vib = vibrator ?: return

        Log.i("HapticService", "Starting haptic thread: mode=$currentMode, rate=$bpm, interval=${intervalMs}ms")

        hapticThread = Thread {
            var consecutiveErrors = 0
            var pulseCount = 0
            try {
                while (isRunning && consecutiveErrors < 10) {
                    try {
                        if (currentMode == "rest") {
                            // Breathing pacer: one pulse = breathe in, two pulses = breathe out.
                            val breathMs = intervalMs
                            val inhaleMs = (breathMs * INHALE_SHARE).toLong()
                            setBreathPhase(inhale = true, phaseMs = inhaleMs)
                            if (!silenced()) vibrateOnce(vib, 120)
                            sleepWhileRunning(inhaleMs)
                            if (!isRunning) break
                            setBreathPhase(inhale = false, phaseMs = breathMs - inhaleMs)
                            if (!silenced()) vibrateTwice(vib)
                            sleepWhileRunning(breathMs - inhaleMs)
                            pulseCount++
                        } else {
                            if (!silenced()) {
                                vibrateOnce(vib, 50)
                                pulseCount++
                            }
                            sleepWhileRunning(intervalMs)
                        }
                        consecutiveErrors = 0
                        if (pulseCount > 0 && pulseCount % 10 == 0) {
                            Log.i("HapticService", "Pulse #$pulseCount, interval=$intervalMs, isRunning=$isRunning")
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

    /** Pulses are skipped during an SpO2 reading or while the person paused the cue. */
    private fun silenced() = pulsesPaused || isUserPaused

    private fun setBreathPhase(inhale: Boolean, phaseMs: Long) {
        breathInhale = inhale
        breathPhaseMs = phaseMs
        breathPhaseStartMs = System.currentTimeMillis()
    }

    private fun vibrateOnce(vib: Vibrator, ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(ms)
        }
    }

    private fun vibrateTwice(vib: Vibrator) {
        val pattern = longArrayOf(0, 60, 120, 60)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(pattern, -1)
        }
    }

    /** Sleeps in short steps so a stop or a rate change takes effect quickly. */
    private fun sleepWhileRunning(ms: Long) {
        var remaining = ms
        while (remaining > 0 && isRunning) {
            try {
                val step = minOf(remaining, 100L)
                Thread.sleep(step)
                remaining -= step
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!isRunning) return
            }
        }
    }

    /**
     * Incremented on every start and stop. A stop finishes (releases the wake lock, leaves the
     * foreground, stops the service) only if nothing started or stopped since — so restarting a
     * cue while the previous file is still being sent doesn't end the new cue.
     */
    @Volatile private var stopGeneration = 0

    private fun stopService() {
        Log.i("HapticService", "Stopping service. isRunning=$isRunning, sessionFile=${sessionFile?.name}")
        if (!isRunning && !isServiceRunning) return
        val generation = ++stopGeneration
        isRunning = false
        isServiceRunning = false
        runningMode = null
        runningBpm = 0
        recordingFileName = null
        isUserPaused = false
        isMeasuringSpo2 = false
        latestBreathing = null
        vitalsTracker?.stop()
        vitalsTracker = null
        pulsesPaused = false
        hapticThread?.interrupt()
        hapticThread = null
        vibrator?.cancel()

        // Stop sensor recording
        sensorListener?.let { sensorManager?.unregisterListener(it) }
        csvWriter?.flush()
        csvWriter?.close()

        // Send file to phone. The wake lock and foreground status are kept alive
        // until the transfer finishes (or times out) — releasing them immediately
        // let Wear OS suspend/kill the process mid-transfer, so the session data
        // never reached the phone.
        val file = sessionFile
        if (file != null && file.exists()) {
            val fileSize = file.length()
            Log.i("HapticService", "Session file exists: ${file.name}, size: $fileSize bytes")
            Log.i("HapticService", "Sending file to phone: ${file.name}")
            sessionSyncManager?.transferAndPurgeFile(file, currentMode) { finishStopping(generation) }
            Log.i("HapticService", "File transfer initiated")
            // Safety net in case the transfer hangs and never calls back (two attempts of 15 s each).
            android.os.Handler(mainLooper).postDelayed({ finishStopping(generation) }, 40_000L)
        } else {
            Log.e("HapticService", "sessionFile is null or does not exist: ${file?.absolutePath}")
            finishStopping(generation)
        }
    }

    @Synchronized
    private fun finishStopping(generation: Int) {
        // Stale (a new cue started, or this stop already finished): leave the service alone.
        if (generation != stopGeneration || isRunning) return
        stopGeneration++
        wakeLock?.let { if (it.isHeld) it.release() }
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
            .setContentTitle(getString(R.string.notif_title, modeLabel(mode)))
            .setContentText(getString(R.string.notif_text, bpm))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "walking" -> getString(R.string.mode_walk)
        "rest" -> getString(R.string.mode_rest)
        "writing" -> getString(R.string.mode_fine)
        "stress" -> getString(R.string.mode_calm)
        else -> mode
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
