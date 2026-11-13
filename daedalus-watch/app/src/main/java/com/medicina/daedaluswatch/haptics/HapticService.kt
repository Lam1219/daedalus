package com.medicina.daedaluswatch.haptics

import com.medicina.daedaluswatch.sensor.HeartRatePermission
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.medicina.daedaluswatch.R
import com.medicina.daedaluswatch.sensor.GuardEngine
import com.medicina.daedaluswatch.sensor.GuardEvent
import com.medicina.daedaluswatch.sensor.GuardSession
import com.medicina.daedaluswatch.sensor.TremorAnalyzer
import com.medicina.daedaluswatch.sensor.VitalsTracker
import com.medicina.daedaluswatch.sync.SessionSyncManager
import com.medicina.daedaluswatch.pilot.PilotPhoneLink
import com.medicina.daedaluswatch.pilot.PilotPresses
import com.medicina.daedaluswatch.pilot.PilotStore
import com.medicina.daedaluswatch.sensor.StandWatchService
import com.medicina.shared.pilot.BeatOutput
import com.medicina.shared.pilot.ClickBeatPlayer
import com.medicina.shared.pilot.Pilot
import com.medicina.shared.pilot.Player
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

/**
 * Foreground service that runs haptic cueing AND records sensor data (HR, accel, gyro) to CSV.
 * Uses WakeLock to prevent the CPU from sleeping.
 *
 * Mode "tremor" is the tremor check instead of a cue: no rhythm pulses, a row for every gyroscope
 * event (about 50 Hz), and it ends on its own after [TremorAnalyzer.CHECK_DURATION_MS] with the
 * result in [lastTremorResult].
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

    private val mainHandler by lazy { android.os.Handler(mainLooper) }
    private val sessionTimeout = Runnable {
        Log.i("HapticService", "Session reached ${MAX_SESSION_MS / 60_000} min: stopping it")
        stopService()
    }
    private val tremorCheckEnd = Runnable {
        Log.i("HapticService", "Tremor check finished")
        stopService()
    }

    /** Gyroscope samples of the running tremor check (sensor time as wall clock, rad/s). */
    private val tremorTimes = ArrayList<Long>()
    private val tremorGx = ArrayList<Double>()
    private val tremorGy = ArrayList<Double>()
    private val tremorGz = ArrayList<Double>()

    private val isTremorCheck get() = currentMode == TremorAnalyzer.MODE

    /** Guard session (walk or rest, shadow mode): records every sample and logs detector events. */
    private val isGuard get() = GuardSession.isGuard(currentMode)
    private var guardEngine: GuardEngine? = null
    private val guardBattery = object : Runnable {
        override fun run() {
            writeBattery()
            mainHandler.postDelayed(this, GuardSession.BATTERY_INTERVAL_MS)
        }
    }

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
        /** During a guard: stop the "I'm stuck" walking rhythm but keep the guard running. */
        const val ACTION_STOP_CUE = "action_stop_cue"
        /** Epoch ms of an "I'm stuck" tap on the watch. In study mode that cue is a pilot press. */
        const val EXTRA_PRESS_MS = "extra_press_ms"

        /** Breathing pacer: inhale share of each breath (In 4 s · Out 6 s at 6 breaths/min). */
        const val INHALE_SHARE = 0.4f
        /** A session the person forgets to stop ends on its own after this long. */
        const val MAX_SESSION_MS = 60 * 60 * 1000L
        /** B6: in study mode a cue stops by itself after 10 minutes instead. */
        const val STUDY_MAX_CUE_MS = Pilot.STUDY_MAX_CUE_S * 1000L
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
        /** A guard is running and "I'm stuck" started the walking rhythm inside it. */
        @Volatile var guardCueActive: Boolean = false
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

        /** Result of the last tremor check, for the watch's result screen; cleared when any session starts. */
        @Volatile var lastTremorResult: TremorAnalyzer.Result? = null
            private set
        @Volatile var lastTremorResultAtMs: Long = 0L
            private set

        /** The watch's result screen was closed (Done). */
        fun clearTremorResult() {
            lastTremorResult = null
        }
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
                // "I'm stuck" during a guard: the walking rhythm starts inside the guard, which keeps recording.
                if (isRunning && isGuard && mode == "walking") startGuardCue(bpm)
                else startForegroundService(bpm, mode, intent.getLongExtra(EXTRA_PRESS_MS, -1L).takeIf { it > 0 })
            }
            ACTION_STOP_CUE -> stopGuardCue()
            ACTION_STOP -> {
                stopService()
            }
            ACTION_PAUSE -> if (isRunning) isUserPaused = true
            ACTION_RESUME -> isUserPaused = false
            ACTION_SET_BPM -> {
                val bpm = intent.getIntExtra(EXTRA_BPM, currentBpm)
                val mode = intent.getStringExtra(EXTRA_MODE)
                if (isRunning && !isTremorCheck && bpm > 0 && (mode == null || mode == currentMode)) updateBpm(bpm)
            }
        }
        return START_STICKY
    }

    private fun startForegroundService(bpm: Int, mode: String, pressMs: Long? = null) {
        if (isRunning) return
        val study = PilotStore.active(this)
        // A pilot press: a walking cue started by "I'm stuck" on the watch while study mode is on.
        pilotPress = study && mode == "walking" && pressMs != null
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
        // A new session replaces the result screen of an earlier tremor check.
        lastTremorResult = null

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

        // Acquire wake lock, releasing one left by the previous cue. It outlasts the session
        // limit so the automatic stop and the file transfer that follows still run.
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Daedalus:HapticWakeLock")
            .apply { acquire(MAX_SESSION_MS + 5 * 60 * 1000L) }
        Log.i("HapticService", "Wake lock acquired")

        // End the session on its own if the person forgets to stop it.
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(tremorCheckEnd)
        if (mode == TremorAnalyzer.MODE) mainHandler.postDelayed(tremorCheckEnd, TremorAnalyzer.CHECK_DURATION_MS)
        else mainHandler.postDelayed(sessionTimeout, sessionLimitMs(study, mode))

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

        // A guard's engine must exist before the first sample arrives.
        if (GuardSession.isGuard(mode)) startGuard()

        // Start sensor recording
        startSensorRecording()

        // Breathing rate and SpO2 (Samsung Health Sensor SDK)
        latestBreathingRate = null
        latestBreathing = null
        latestSpo2 = null
        if (!GuardSession.isGuard(mode)) vitalsTracker = VitalsTracker(
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

        // Start haptic thread; the tremor check has no rhythm, only one short buzz to begin.
        // A guard plays no rhythm either (shadow mode): one short buzz to say it started.
        if (mode == TremorAnalyzer.MODE || GuardSession.isGuard(mode)) vibrator?.let { vibrateOnce(it, 60) }
        else if (pilotPress) {
            // The motion ring must be running for the press file (it normally already is).
            StandWatchService.refresh(this)
            PilotPresses.begin(this, pressMs!!, bpm, beatOutput)
        }
        else startHapticThread(bpm)

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
        csvWriter?.write(
            if (GuardSession.isGuard(mode)) GuardSession.HEADER
            else "timestamp_ms,heart_rate_bpm,ax,ay,az,gx,gy,gz,breathing_rate_brpm,spo2_percent\n"
        )
        csvWriter?.flush()
    }

    private fun startSensorRecording() {
        tremorTimes.clear(); tremorGx.clear(); tremorGy.clear(); tremorGz.clear()
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

                // Tremor check: one row per gyroscope event (~50 Hz), at the event's own time.
                if (isTremorCheck) {
                    if (event.sensor.type == Sensor.TYPE_GYROSCOPE) writeTremorRow(event)
                    return
                }

                // Guard: the same, and every sample goes through the guard engine.
                if (isGuard) {
                    if (event.sensor.type == Sensor.TYPE_GYROSCOPE) writeGuardSample(event)
                    return
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

            private fun writeTremorRow(event: SensorEvent) {
                val time = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
                tremorTimes.add(time)
                tremorGx.add(lastGyro[0].toDouble())
                tremorGy.add(lastGyro[1].toDouble())
                tremorGz.add(lastGyro[2].toDouble())
                val breathing = latestBreathingRate?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                val spo2 = latestSpo2?.toString() ?: ""
                latestSpo2 = null
                csvWriter?.write("$time,$lastHr,${lastAccel[0]},${lastAccel[1]},${lastAccel[2]},${lastGyro[0]},${lastGyro[1]},${lastGyro[2]},$breathing,$spo2\n")
            }

            private fun writeGuardSample(event: SensorEvent) {
                val time = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
                csvWriter?.write(GuardSession.sampleRow(time, lastHr, lastAccel[0], lastAccel[1], lastAccel[2], lastGyro[0], lastGyro[1], lastGyro[2]))
                val rows = guardEngine?.push(
                    time,
                    lastAccel[0].toDouble(), lastAccel[1].toDouble(), lastAccel[2].toDouble(),
                    lastGyro[0].toDouble(), lastGyro[1].toDouble(), lastGyro[2].toDouble(),
                ) ?: return
                writeGuardEvents(rows)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let { sensorManager?.registerListener(sensorListener, it, 20000) } // 50Hz
        gyroscope?.let { sensorManager?.registerListener(sensorListener, it, 20000) }

        if (heartRateSensor != null) {
            if (HeartRatePermission.isGranted(this)) {
                sensorManager?.registerListener(sensorListener, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                Log.w("HapticService", "Heart rate sensor present but ${HeartRatePermission.name} not granted — recording 0 BPM. Open the watch app once to grant permissions.")
            }
        }

        Log.i("HapticService", "Sensor recording started: accel=${accelerometer != null}, gyro=${gyroscope != null}, heartRate=${heartRateSensor != null}")
    }

    // ------------------------------------------------------------------ guard sessions

    /** Engine, GUARD_START and the first battery reading; then a battery reading every 5 minutes. */
    private fun startGuard() {
        guardCueActive = false
        val engine = GuardEngine(GuardSession.engineMode(currentMode))
        guardEngine = engine
        writeGuardEvents(engine.begin(System.currentTimeMillis()))
        mainHandler.removeCallbacks(guardBattery)
        mainHandler.post(guardBattery)
    }

    /** The last battery reading, an open tremor episode's end and GUARD_END, before the file is closed. */
    private fun finishGuard() {
        mainHandler.removeCallbacks(guardBattery)
        if (guardCueActive) {
            guardCueActive = false
            writeGuardEvents(listOf(GuardSession.stuckCueEnd(System.currentTimeMillis())))
        }
        writeBattery()
        guardEngine?.let { writeGuardEvents(it.finish(System.currentTimeMillis())) }
        guardEngine = null
    }

    /** "I'm stuck" during a guard: the walking rhythm at the person's rate, inside the guard. */
    private fun startGuardCue(bpm: Int) {
        if (!isGuard || guardCueActive) return
        guardCueActive = true
        currentBpm = bpm
        runningBpm = bpm
        writeGuardEvents(listOf(GuardSession.stuckCueStart(System.currentTimeMillis(), bpm)))
        startHapticThread(bpm)
        Log.i("HapticService", "I'm stuck during $currentMode: walking rhythm at $bpm")
    }

    /** Stops only the rhythm; the guard keeps recording. */
    private fun stopGuardCue() {
        if (!isGuard || !guardCueActive) return
        guardCueActive = false
        hapticThread?.interrupt()
        hapticThread = null
        vibrator?.cancel()
        writeGuardEvents(listOf(GuardSession.stuckCueEnd(System.currentTimeMillis())))
    }

    private fun writeGuardEvents(rows: List<GuardEvent>) {
        for (e in rows) csvWriter?.write(GuardSession.eventRow(e))
    }

    /** Battery percent (-1 when unknown) and whether the watch is on its charger (a full battery still plugged in counts). */
    private fun writeBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val raw = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val percent = if (raw in 0..100) raw else -1
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val onCharger = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        writeGuardEvents(listOf(GuardSession.battery(System.currentTimeMillis(), percent, onCharger)))
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
                while (isRunning && cueOn() && consecutiveErrors < 10) {
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
                                firstPulse?.let { firstPulse = null; it(System.currentTimeMillis(), "watch_motor") }
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

    // ------------------------------------------------------------------ home pilot

    /** The running cue is a pilot press: the beat plays where PilotPresses drew it. */
    @Volatile private var pilotPress = false
    /** Called once with the time of the first wrist pulse (beat_start_ms for the wrist). */
    @Volatile private var firstPulse: ((Long, String) -> Unit)? = null
    private var clickPlayer: ClickBeatPlayer? = null

    /** B6: 10 minutes for a cue in study mode; guards and everything outside study mode keep the hour. */
    private fun sessionLimitMs(study: Boolean, mode: String) =
        if (study && !GuardSession.isGuard(mode)) STUDY_MAX_CUE_MS else MAX_SESSION_MS

    /** Plays a press's beat on the player the draw chose (or the wrist, when that fails). */
    private val beatOutput = object : BeatOutput {
        override fun start(player: Player, bpm: Int, onStarted: (Long, String) -> Unit, onFailed: (String) -> Unit) {
            when (player) {
                Player.WATCH_HAPTIC -> {
                    firstPulse = onStarted
                    mainHandler.post { if (isRunning && hapticThread == null) startHapticThread(bpm) }
                }
                Player.WATCH_AUDIO -> clickPlayer = ClickBeatPlayer(this@HapticService).also {
                    it.start(bpm, onStarted, onFailed, onLost = { reason -> PilotPresses.current?.runner?.lost(reason) })
                }
                Player.PHONE_AUDIO, Player.PHONE_VIBRATION -> {
                    val id = PilotPresses.current?.id ?: return onFailed("no_press")
                    PilotPhoneLink.startBeat(this@HapticService, id, player, bpm, STUDY_MAX_CUE_MS, onStarted, onFailed)
                }
            }
        }

        override fun stop(player: Player) {
            when (player) {
                Player.WATCH_HAPTIC -> Unit // stops with the service
                Player.WATCH_AUDIO -> { clickPlayer?.stop(); clickPlayer = null }
                Player.PHONE_AUDIO, Player.PHONE_VIBRATION ->
                    PilotPresses.current?.id?.let { PilotPhoneLink.stopBeat(this@HapticService, it) }
            }
        }
    }

    /** In a guard the rhythm plays only while "I'm stuck" started it. */
    private fun cueOn() = !isGuard || guardCueActive

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
        while (remaining > 0 && isRunning && cueOn()) {
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
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(tremorCheckEnd)
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
        if (pilotPress) {
            pilotPress = false
            PilotPresses.onBeatStopped(this, System.currentTimeMillis())
            clickPlayer?.stop()
            clickPlayer = null
        }

        // Stop sensor recording
        sensorListener?.let { sensorManager?.unregisterListener(it) }
        if (isGuard) finishGuard()
        csvWriter?.flush()
        csvWriter?.close()
        csvWriter = null

        if (isTremorCheck) finishTremorCheck()

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
            mainHandler.postDelayed({ finishStopping(generation) }, 40_000L)
        } else {
            Log.e("HapticService", "sessionFile is null or does not exist: ${file?.absolutePath}")
            finishStopping(generation)
        }
    }

    /** Double buzz to say the check is over, then analyses its samples off the main thread. */
    private fun finishTremorCheck() {
        vibrator?.let { vibrateTwice(it) }
        val times = tremorTimes.toLongArray()
        val gx = tremorGx.toDoubleArray()
        val gy = tremorGy.toDoubleArray()
        val gz = tremorGz.toDoubleArray()
        tremorTimes.clear(); tremorGx.clear(); tremorGy.clear(); tremorGz.clear()
        Thread {
            val result = TremorAnalyzer.analyze(times, gx, gy, gz)
            Log.i("HapticService", "Tremor check: ${result.outcome} ${result.frequencyHz ?: ""} Hz, ${times.size} samples")
            // A session started meanwhile has its own screen: don't show this result over it.
            if (!isServiceRunning) {
                lastTremorResultAtMs = System.currentTimeMillis()
                lastTremorResult = result
            }
        }.apply { name = "TremorAnalysis" }.start()
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
            .setContentText(
                when {
                    mode == TremorAnalyzer.MODE -> getString(R.string.tremor_rest_hand)
                    GuardSession.isGuard(mode) -> getString(R.string.guard_notif_text)
                    else -> getString(R.string.notif_text, bpm)
                }
            )
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
        TremorAnalyzer.MODE -> getString(R.string.tremor_check_title)
        GuardSession.MODE_WALK_GUARD -> getString(R.string.mode_walk_guard)
        GuardSession.MODE_REST_GUARD -> getString(R.string.mode_rest_guard)
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
