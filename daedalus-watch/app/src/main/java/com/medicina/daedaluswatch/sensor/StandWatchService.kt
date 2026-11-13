package com.medicina.daedaluswatch.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.medicina.daedaluswatch.R
import com.medicina.daedaluswatch.haptics.HapticService
import com.medicina.daedaluswatch.pilot.PilotPhoneLink
import com.medicina.daedaluswatch.pilot.PilotPresses
import com.medicina.daedaluswatch.pilot.PilotStore
import com.medicina.daedaluswatch.sync.SessionSyncManager
import com.medicina.daedaluswatch.ui.StandPromptActivity
import com.medicina.shared.pilot.PhoneStatus
import com.medicina.shared.pilot.Pilot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "StandWatchService"

/**
 * Background stand-up detection while "Stand-up prompt" is on in the phone app.
 * Listens to the barometer and accelerometer at 10 Hz; on a detected sit-to-stand it
 * vibrates gently and shows the "Pause before walking" prompt.
 *
 * Home pilot (study mode on): the service also runs all day for
 * - B4, the motion ring: accelerometer and gyroscope at 50 Hz, batched, kept in memory only
 *   ([PilotPresses.ring]); a press takes its 90 s from it. A battery line every 15 minutes goes
 *   to a daily file, for the 14-hour battery test.
 * - B10, the pocket reminder: after a stand-up it asks the phone whether it counted steps or
 *   moved in the last minute; if not, "Phone in your front pocket?" (a line on the stand-up
 *   prompt when that is on, otherwise on its own).
 * With study mode off nothing of this runs and the stand-up prompt works as before.
 */
class StandWatchService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private val detector = StandDetector()
    private val main = Handler(Looper.getMainLooper())

    /** What is registered now, so a settings change only re-registers what changed. */
    private var standOn = false
    private var ringOn = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundNotification()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        instance = this
        configure()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        configure()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = Notification.Builder(this, CHANNEL_WATCHING)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (PilotStore.active(this)) R.string.pilot_watching else R.string.stand_watching))
            .setSmallIcon(R.drawable.ic_stand)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_WATCHING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_WATCHING, notification)
        }
    }

    /** Registers the stand-up sensors and the pilot's motion ring as the settings ask. */
    private fun configure() {
        val sm = sensorManager ?: return
        val pilot = PilotStore.settings(this)
        val wantStand = isEnabled(this) || (pilot.active && pilot.pocketReminder)
        val wantRing = pilot.active
        if (!wantStand && !wantRing) { stopSelf(); return }
        startForegroundNotification() // text follows study mode

        if (wantStand != standOn) {
            if (wantStand) {
                val barometer = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
                val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (barometer == null || accel == null) {
                    Log.w(TAG, "Barometer or accelerometer missing; stand-up detection unavailable")
                } else {
                    // 10 Hz, delivered within 1 s (batching saves power; a prompt 1 s late is fine).
                    sm.registerListener(this, barometer, 100_000, 1_000_000)
                    sm.registerListener(this, accel, 100_000, 1_000_000)
                    standOn = true
                    Log.i(TAG, "Stand-up detection started")
                }
            } else {
                sm.unregisterListener(this)
                standOn = false
            }
        }
        if (wantRing != ringOn) {
            if (wantRing) startRing(sm) else stopRing(sm)
        }
        if (!standOn && !ringOn) stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Event timestamps are nanoseconds since boot; batched events keep their real times.
        val tMs = event.timestamp / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> detector.onAccel(tMs, event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_PRESSURE -> if (detector.onPressure(tMs, event.values[0])) {
                // Ignore detections from stale batched samples.
                if (SystemClock.elapsedRealtime() - tMs < 5_000) onStandUp()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onStandUp() {
        Log.i(TAG, "Stand-up detected")
        if (isEnabled(this)) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 150, 80), -1))

            val open = Intent(this, StandPromptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val prompt = Notification.Builder(this, CHANNEL_PROMPT)
                .setContentTitle(getString(R.string.stand_title))
                .setContentText(getString(R.string.stand_sub))
                .setSmallIcon(R.drawable.ic_stand)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_PROMPT, prompt)
        }
        checkPocket()
    }

    // ------------------------------------------------------------------ B10 pocket reminder

    private fun checkPocket() {
        val pilot = PilotStore.settings(this)
        if (!pilot.active || !pilot.pocketReminder) return
        // Never during a beat; the rule says so too, but don't even ask the phone.
        if (HapticService.isServiceRunning) return
        val id = "stand-${System.currentTimeMillis()}"
        pocketQuery = id
        PilotPhoneLink.query(this, id)
        // No reply in time: the phone is out of reach, so it isn't in the pocket either.
        main.postDelayed({ if (pocketQuery == id) decidePocket(id, null) }, POCKET_REPLY_MS)
    }

    private fun decidePocket(id: String, reply: PhoneStatus?) {
        if (pocketQuery != id) return
        pocketQuery = null
        val now = System.currentTimeMillis()
        val pilot = PilotStore.settings(this)
        val stepsLastMin = if (PilotStore.phoneCarried(now)) 1 else 0
        val remind = Pilot.shouldRemindPocket(
            nowMs = now, stoodUp = true, phoneStepsLastMin = stepsLastMin, phoneMovedLastMin = reply?.moved == true,
            lastReminderMs = PilotStore.lastReminderMs(this), beatPlaying = HapticService.isServiceRunning,
            enabled = pilot.active && pilot.pocketReminder,
        )
        Log.i(TAG, "Pocket check: reply=${reply != null}, steps=$stepsLastMin, moved=${reply?.moved}, remind=$remind")
        if (!remind) return
        PilotStore.setLastReminderMs(this, now)
        // A gentle tap, lighter than the stand-up buzz.
        (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(40, 120))
        val withStandPrompt = isEnabled(this)
        val open = Intent(this, StandPromptActivity::class.java)
            .putExtra(StandPromptActivity.EXTRA_POCKET, true)
            .putExtra(StandPromptActivity.EXTRA_POCKET_ONLY, !withStandPrompt)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (withStandPrompt && StandPromptActivity.isVisible) {
            // The stand-up prompt is on screen: add the line to it.
            runCatching { startActivity(open) }.onFailure { Log.w(TAG, "Couldn't add the pocket line: ${it.message}") }
            return
        }
        val pending = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL_PROMPT)
            .setContentTitle(getString(R.string.pilot_pocket_title))
            .setSmallIcon(R.drawable.ic_stand)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_POCKET, n)
    }

    // ------------------------------------------------------------------ B4 motion ring

    private var lastGyro = FloatArray(3)
    private var hasGyro = false

    private val ringListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> { lastGyro[0] = e.values[0]; lastGyro[1] = e.values[1]; lastGyro[2] = e.values[2] }
                Sensor.TYPE_ACCELEROMETER -> {
                    // Boot-time nanoseconds to epoch ms; one row per accelerometer sample, with the latest rotation.
                    val t = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
                    PilotPresses.ring.add(t, e.values[0], e.values[1], e.values[2], lastGyro[0], lastGyro[1], lastGyro[2])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Prefers the wake-up sensors: batched samples then never get dropped while the watch sleeps. */
    private fun sensor(sm: SensorManager, type: Int): Sensor? = sm.getDefaultSensor(type, true) ?: sm.getDefaultSensor(type)

    private fun startRing(sm: SensorManager) {
        val acc = sensor(sm, Sensor.TYPE_ACCELEROMETER) ?: return Unit.also { Log.w(TAG, "No accelerometer: no motion ring") }
        val gyr = sensor(sm, Sensor.TYPE_GYROSCOPE)
        hasGyro = gyr != null
        PilotPresses.ringHasGyro = hasGyro
        sm.registerListener(ringListener, acc, RING_PERIOD_US, RING_LATENCY_US)
        gyr?.let { sm.registerListener(ringListener, it, RING_PERIOD_US, RING_LATENCY_US) }
        ringOn = true
        writeBatteryHeader(acc, gyr)
        main.removeCallbacks(batteryTick)
        main.post(batteryTick)
        // A restart (crash, kill, update) may have left presses pending (round 001, #5).
        PilotPresses.recoverOrphans(this)
        Log.i(TAG, "Motion ring started: acc wake-up=${acc.isWakeUpSensor} fifo=${acc.fifoMaxEventCount}, gyro=${gyr != null}")
    }

    private fun stopRing(sm: SensorManager) {
        sm.unregisterListener(ringListener)
        main.removeCallbacks(batteryTick)
        PilotPresses.ring.clear()
        ringOn = false
    }

    // ------------------------------------------------------------------ battery log

    private val batteryTick = object : Runnable {
        override fun run() {
            writeBattery()
            main.postDelayed(this, BATTERY_INTERVAL_MS)
        }
    }

    private fun batteryFile(day: Date = Date()): File {
        val code = PilotStore.settings(this).participant.ifBlank { "unknown" }
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(day)
        return File(PilotPresses.pilotDir(this).apply { mkdirs() }, "battery_${code}_$stamp.csv")
    }

    private fun writeBatteryHeader(acc: Sensor, gyr: Sensor?) {
        val f = batteryFile()
        if (!f.exists()) f.writeText("time_ms,battery_percent,on_charger,ring_samples_total,note\n")
        f.appendText(
            "${System.currentTimeMillis()},,,," +
                "ring start: acc wake-up ${acc.isWakeUpSensor} fifo ${acc.fifoMaxEventCount}/${acc.fifoReservedEventCount}; " +
                "gyro ${gyr?.let { "wake-up ${it.isWakeUpSensor} fifo ${it.fifoMaxEventCount}/${it.fifoReservedEventCount}" } ?: "none"}\n",
        )
    }

    /** Battery percent, charger, and samples received so far (they should grow by ~45,000 every 15 min). */
    private fun writeBattery() {
        runCatching {
            val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
            val raw = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val onCharger = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val f = batteryFile()
            if (!f.exists()) {
                f.writeText("time_ms,battery_percent,on_charger,ring_samples_total,note\n")
                // A new day: send yesterday's (and any older) battery file to the phone.
                SessionSyncManager(this).syncPilotFiles()
            }
            f.appendText("${System.currentTimeMillis()},${if (raw in 0..100) raw else ""},$onCharger,${PilotPresses.ring.added},\n")
        }.onFailure { Log.w(TAG, "Battery log: ${it.message}") }
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        sensorManager?.let { if (ringOn) stopRing(it) }
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        Log.i(TAG, "Stand-up detection stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCHING, getString(R.string.stand_watching_channel), NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROMPT, getString(R.string.stand_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        private const val PREFS = "stand_prompt"
        private const val KEY_ENABLED = "enabled"
        private const val CHANNEL_WATCHING = "daedalus_stand_watching"
        private const val CHANNEL_PROMPT = "daedalus_stand_prompt"
        private const val NOTIFICATION_WATCHING = 21
        private const val NOTIFICATION_PROMPT = 22
        private const val NOTIFICATION_POCKET = 23

        /** 50 Hz, delivered in batches of up to 10 s: the processor sleeps between batches. */
        private const val RING_PERIOD_US = 20_000
        private const val RING_LATENCY_US = 10_000_000
        private const val BATTERY_INTERVAL_MS = 15 * 60 * 1000L
        /** How long the watch waits for the phone's answer after a stand-up. */
        private const val POCKET_REPLY_MS = 3_000L

        @Volatile private var instance: StandWatchService? = null
        @Volatile private var pocketQuery: String? = null

        /** A status reply from the phone (WatchMessageListener); settles a pending pocket check. */
        fun onPhoneStatus(s: PhoneStatus) {
            val svc = instance ?: return
            val id = pocketQuery ?: return
            if (s.queryId == id) svc.main.post { svc.decidePocket(id, s) }
        }

        /** Delivers batched ring samples now (a press is about to read the ring). */
        fun flushSensors() {
            val svc = instance ?: return
            if (svc.ringOn) svc.sensorManager?.flush(svc.ringListener)
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /** Saves the phone's "Stand-up prompt" choice and starts or stops detection. */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            refresh(context)
        }

        fun startIfEnabled(context: Context) = refresh(context)

        /** Runs the service while the stand-up prompt or study mode needs it, and stops it otherwise. */
        fun refresh(context: Context) {
            val needed = isEnabled(context) || PilotStore.active(context)
            val intent = Intent(context, StandWatchService::class.java)
            if (needed) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { Log.w(TAG, "Could not start stand-up detection: ${it.message}") }
            } else {
                context.stopService(intent)
            }
        }
    }
}
