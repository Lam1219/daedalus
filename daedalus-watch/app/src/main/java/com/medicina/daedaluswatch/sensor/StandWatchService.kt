package com.medicina.daedaluswatch.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.medicina.daedaluswatch.R
import com.medicina.daedaluswatch.ui.StandPromptActivity

private const val TAG = "StandWatchService"

/**
 * Background stand-up detection while "Stand-up prompt" is on in the phone app.
 * Listens to the barometer and accelerometer at 10 Hz; on a detected sit-to-stand it
 * vibrates gently and shows the "Pause before walking" prompt.
 */
class StandWatchService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private val detector = StandDetector()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notification = Notification.Builder(this, CHANNEL_WATCHING)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.stand_watching))
            .setSmallIcon(R.drawable.ic_stand)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_WATCHING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_WATCHING, notification)
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val barometer = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (barometer == null || accel == null) {
            Log.w(TAG, "Barometer or accelerometer missing; stand-up detection unavailable")
            stopSelf()
            return
        }
        // 10 Hz, delivered within 1 s (batching saves power; a prompt 1 s late is fine).
        sensorManager?.registerListener(this, barometer, 100_000, 1_000_000)
        sensorManager?.registerListener(this, accel, 100_000, 1_000_000)
        Log.i(TAG, "Stand-up detection started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
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

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /** Saves the phone's "Stand-up prompt" choice and starts or stops detection. */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            val intent = Intent(context, StandWatchService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }

        fun startIfEnabled(context: Context) {
            if (isEnabled(context)) {
                runCatching { context.startForegroundService(Intent(context, StandWatchService::class.java)) }
                    .onFailure { Log.w(TAG, "Could not start stand-up detection: ${it.message}") }
            }
        }
    }
}
