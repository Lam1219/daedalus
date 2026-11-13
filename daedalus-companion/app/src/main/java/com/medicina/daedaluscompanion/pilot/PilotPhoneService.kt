package com.medicina.daedaluscompanion.pilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.medicina.daedaluscompanion.R
import com.medicina.shared.pilot.AudioRoutes
import com.medicina.shared.pilot.BeatMessage
import com.medicina.shared.pilot.ClickBeatPlayer
import com.medicina.shared.pilot.PhoneStatus
import com.medicina.shared.pilot.PilotLink
import com.medicina.shared.pilot.Player

private const val TAG = "PilotPhoneService"

/**
 * The phone's part of the home pilot, running all day while study mode is on:
 * - "phone carried": the step counter (batched, wake-up when the phone has one) and the time of
 *   the last step, pushed to the watch so a press never waits for the phone;
 * - "phone moved" for the pocket reminder: the accelerometer at 10 Hz, batched, flushed on a query;
 * - earbuds connected or not, pushed to the watch when it changes;
 * - B9 / B2: plays a press's beat when the watch asks, as strong short vibrations (pocket) or
 *   clicks in the earbuds, and says when the first one actually played.
 * A foreground service, so vibration keeps working with the screen off and the phone in a pocket.
 */
class PilotPhoneService : Service() {

    private lateinit var sm: SensorManager
    private lateinit var am: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val steps = StepTracker()
    private val movement = MovementWindow()
    private var lastPushMs = 0L
    private var lastPushedCarried = false
    private var lastEarbuds: Boolean? = null
    private var watchNode: String? = null

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val ms = e.timestamp / 1_000_000
            when (e.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> steps.onCounter(ms, e.values[0])
                Sensor.TYPE_STEP_DETECTOR -> steps.onStep(ms)
            }
            maybePush()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private var flushWaiters = 0
    private var onFlushed: (() -> Unit)? = null

    private val motionListener = object : SensorEventListener2 {
        override fun onSensorChanged(e: SensorEvent) {
            movement.add(e.timestamp / 1_000_000, e.values[0], e.values[1], e.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onFlushCompleted(sensor: Sensor?) { main.post { flushDone() } }
    }

    private val stepFlush = object : SensorEventListener2 {
        override fun onSensorChanged(e: SensorEvent) = stepListener.onSensorChanged(e)
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onFlushCompleted(sensor: Sensor?) { main.post { flushDone() } }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = maybePush()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = maybePush()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (!startInForeground()) { stopSelf(); return }
        registerSensors()
        am.registerAudioDeviceCallback(audioCallback, main)
        Log.i(TAG, "Pilot phone service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PilotPrefs.settings(this).active) stopSelf()
        return START_STICKY
    }

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.pilot_notif_channel), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.pilot_notif_title))
            .setContentText(getString(R.string.pilot_notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (e: Exception) {
            // Android 14+: the health type needs "Physical activity" allowed (asked when study mode is turned on).
            Log.e(TAG, "Couldn't start in the foreground: ${e.message}")
            false
        }
    }

    /** Prefers the wake-up variant: batched steps then wake the phone instead of being dropped. */
    private fun sensor(type: Int): Sensor? = sm.getDefaultSensor(type, true) ?: sm.getDefaultSensor(type)

    private fun registerSensors() {
        val counter = sensor(Sensor.TYPE_STEP_COUNTER)
        val detector = if (counter == null) sensor(Sensor.TYPE_STEP_DETECTOR) else null
        (counter ?: detector)?.let {
            sm.registerListener(stepFlush, it, SensorManager.SENSOR_DELAY_NORMAL, STEP_LATENCY_US)
            Log.i(TAG, "Steps from ${it.name} (wake-up ${it.isWakeUpSensor}, fifo ${it.fifoMaxEventCount})")
        } ?: Log.w(TAG, "No step sensor: the pocket place is never available on this phone")
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(motionListener, it, 100_000, MOTION_LATENCY_US)
        }
    }

    // ------------------------------------------------------------------ status to the watch

    private fun status(queryId: String = "", moved: Boolean? = null): PhoneStatus {
        val nowBoot = SystemClock.elapsedRealtime()
        // Round 004: earbuds don't count until the earbuds device check passed for this participant.
        val earbuds = PilotPhoneRules.earbudsForStatus(PilotPrefs.settings(this), AudioRoutes.headphonesConnected(this))
        return PhoneStatus(earbuds, steps.agoMs(nowBoot), moved, queryId)
    }

    /** Pushes when the watch's view would change: earbuds, or fresh steps (at most every 15 s). */
    private fun maybePush() {
        val s = status()
        val carried = s.lastStepAgoMs?.let { it <= 60_000 } ?: false
        val now = SystemClock.elapsedRealtime()
        val changed = s.earbuds != lastEarbuds || (carried && (!lastPushedCarried || now - lastPushMs >= PUSH_EVERY_MS))
        if (!changed) return
        lastEarbuds = s.earbuds
        lastPushedCarried = carried
        lastPushMs = now
        send(PilotLink.STATUS_PATH, s.encode())
    }

    /** A watch query: flush the batched sensors, then reply with steps and movement. */
    fun onQuery(queryId: String, fromNode: String) {
        watchNode = fromNode
        main.post {
            flushWaiters = 2
            var answered = false
            onFlushed = {
                if (!answered) {
                    answered = true
                    send(PilotLink.STATUS_PATH, status(queryId, movement.moved(SystemClock.elapsedRealtime())).encode())
                }
            }
            if (!sm.flush(stepFlush)) flushDone()
            if (!sm.flush(motionListener)) flushDone()
            main.postDelayed({ flushWaiters = 0; onFlushed?.invoke(); onFlushed = null }, FLUSH_WAIT_MS)
        }
    }

    private fun flushDone() {
        if (flushWaiters <= 0) return
        if (--flushWaiters == 0) { onFlushed?.invoke(); onFlushed = null }
    }

    // ------------------------------------------------------------------ beats

    private var beatId: String? = null
    private var beatThread: Thread? = null
    @Volatile private var beating = false
    @Volatile private var beatPaused = false
    private var clicks: ClickBeatPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val beatCap = Runnable { stopBeat(beatId ?: "") }

    fun onBeat(m: BeatMessage, fromNode: String, receivedMs: Long) {
        watchNode = fromNode
        main.post {
            when (m) {
                is BeatMessage.Start -> startBeat(m, receivedMs)
                is BeatMessage.Stop -> stopBeat(m.pressId)
                // Round 001, #24: pause and resume follow the watch; the beat keeps its timing.
                is BeatMessage.Pause -> if (m.pressId == beatId) { beatPaused = m.paused; clicks?.paused = m.paused }
                else -> Unit
            }
        }
    }

    private fun startBeat(m: BeatMessage.Start, receivedMs: Long) {
        // Round 005, #5: refuse at once (the watch's wrist takes over well within 1 s).
        if (!PilotPhoneRules.mayPlay(m.player, PilotPrefs.settings(this))) {
            Log.w(TAG, "Refused ${m.player} for ${m.pressId}: earbuds not checked for this participant")
            send(PilotLink.BEAT_PATH, BeatMessage.Failed(m.pressId, "earbuds_not_checked").encode())
            return
        }
        stopBeat(beatId ?: "")
        beatId = m.pressId
        beatPaused = false
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Daedalus:PilotBeat").apply { acquire(m.maxMs + 60_000) }
        main.postDelayed(beatCap, m.maxMs) // the watch's 10-minute limit, in case its stop never arrives
        // t null: playing, start not measured yet (sent as -1); the measured start follows (round 002, #8).
        val started = { t: Long?, source: String ->
            send(PilotLink.BEAT_PATH, BeatMessage.Started(m.pressId, receivedMs, t ?: -1, System.currentTimeMillis(), source).encode())
        }
        val failed = { reason: String ->
            main.post { if (beatId == m.pressId) stopBeat(m.pressId) }
            send(PilotLink.BEAT_PATH, BeatMessage.Failed(m.pressId, reason).encode())
        }
        when (m.player) {
            Player.PHONE_VIBRATION -> startVibration(m.bpm, started, failed)
            Player.PHONE_AUDIO -> clicks = ClickBeatPlayer(this).also {
                it.start(m.bpm, onStarted = { t, src -> started(t, src) }, onFailed = failed, onLost = failed)
            }
            else -> failed("not_a_phone_player")
        }
        Log.i(TAG, "Beat ${m.pressId}: ${m.player} at ${m.bpm}")
    }

    private fun stopBeat(pressId: String) {
        if (beatId == null || (pressId.isNotEmpty() && pressId != beatId)) return
        beatId = null
        main.removeCallbacks(beatCap)
        beating = false
        beatThread?.interrupt(); beatThread = null
        vibrator().cancel()
        clicks?.stop(); clicks = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
    }

    /**
     * B9: strong, short pulses at the pace, each one timed from the first so they never drift.
     * Usage ALARM: alarm vibrations follow the alarm settings, not the ringer, so silent mode doesn't
     * mute them (Do Not Disturb lets alarms through by default, but the person can change that).
     * Vibrating with the screen off works because this is a foreground service; the docs don't say
     * so, AOSP's vibrator service does (handoff: platform notes, checked on a device).
     */
    private fun startVibration(bpm: Int, started: (Long, String) -> Unit, failed: (String) -> Unit) {
        val vib = vibrator()
        if (!vib.hasVibrator()) return failed("no_vibrator")
        val effect = VibrationEffect.createOneShot(PULSE_MS, if (vib.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE)
        val play: () -> Unit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM);
            { vib.vibrate(effect, attrs) }
        } else {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
            { @Suppress("DEPRECATION") vib.vibrate(effect, attrs) }
        }
        beating = true
        beatThread = Thread {
            val periodNs = 60_000_000_000L / bpm
            val t0 = System.nanoTime()
            var k = 0L
            try {
                while (beating) {
                    if (!beatPaused) play()
                    if (k == 0L) started(System.currentTimeMillis(), "vibration")
                    k++
                    val wait = (t0 + k * periodNs - System.nanoTime()) / 1_000_000
                    if (wait > 0) Thread.sleep(wait)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.e(TAG, "Vibration stopped: ${e.message}")
                if (k == 0L) failed("vibration_error") else failed("vibration_lost")
            }
        }.apply { name = "PocketBeat"; priority = Thread.MAX_PRIORITY; start() }
    }

    // ------------------------------------------------------------------ link

    private fun send(path: String, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val client = Wearable.getMessageClient(this)
        val node = watchNode
        if (node != null) {
            client.sendMessage(node, path, bytes).addOnFailureListener { Log.w(TAG, "$path: ${it.message}") }
            return
        }
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            for (n in nodes) client.sendMessage(n.id, path, bytes)
        }
    }

    override fun onDestroy() {
        stopBeat(beatId ?: "")
        runCatching { sm.unregisterListener(stepFlush); sm.unregisterListener(motionListener) }
        runCatching { am.unregisterAudioDeviceCallback(audioCallback) }
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "daedalus_pilot"
        private const val NOTIFICATION_ID = 41
        private const val STEP_LATENCY_US = 10_000_000
        private const val MOTION_LATENCY_US = 30_000_000
        private const val PUSH_EVERY_MS = 15_000L
        private const val FLUSH_WAIT_MS = 600L
        /** Strong and short: felt through trouser fabric without blurring into the next pulse. */
        private const val PULSE_MS = 60L

        @Volatile var instance: PilotPhoneService? = null
            private set

        /** Runs the service while study mode is on, and stops it when it goes off. */
        fun refresh(c: Context) {
            val intent = Intent(c, PilotPhoneService::class.java)
            // Android 14+ refuses a health foreground service without "physical activity", and a
            // foreground service that never reaches the foreground crashes the app: don't start it then.
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                c.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (PilotPrefs.settings(c).active && allowed) {
                runCatching { c.startForegroundService(intent) }.onFailure { Log.w(TAG, "Couldn't start: ${it.message}") }
            } else {
                c.stopService(intent)
            }
        }
    }
}

/** Starts the pilot service again after a restart or an app update. */
class PilotBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PilotPhoneService.refresh(context)
        }
    }
}
