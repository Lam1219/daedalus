package com.medicina.daedaluswatch.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.medicina.daedaluswatch.R
import com.medicina.daedaluswatch.ble.IphoneBLECentral
import com.medicina.daedaluswatch.data.SessionDataLogger
import com.medicina.daedaluswatch.haptics.HapticEngine
import com.medicina.daedaluswatch.model.EventEntry
import com.medicina.daedaluswatch.model.EventLabels
import com.medicina.daedaluswatch.model.SessionMetadata
import kotlinx.coroutines.*

private const val TAG = "SensorCollectionService"
private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "daedalus_sensor_channel"

// Named constants for sensor configuration
private const val IMU_SAMPLING_PERIOD_US = 20000  // 50 Hz = 20ms = 20000μs
private const val BLE_FORWARD_INTERVAL = 10  // Forward to iPhone every N samples

/**
 * Foreground service that runs sensor collection in the background.
 * This is required to keep the watch app running during the 60-minute protocol.
 *
 * Integrates:
 * - IMU sensor collection at 50 Hz
 * - HR monitoring via Health Services API
 * - Haptic engine
 * - BLE forwarding to iPhone (optional)
 */
class SensorCollectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val binder = LocalBinder()
    private lateinit var imuSensorManager: ImuSensorManager
    private lateinit var healthSensorManager: SensorManager
    private lateinit var dataLogger: SessionDataLogger
    lateinit var hapticEngine: HapticEngine
    lateinit var iphoneBLE: IphoneBLECentral

    private var imuUnregister: (() -> Unit)? = null
    private var hrCollectionJob: Job? = null
    private var isCollecting = false
    private var imuSampleCounter = 0
    private var hrSampleCounter = 0

    // Current session info
    private var currentMetadata: SessionMetadata? = null

    inner class LocalBinder : Binder() {
        fun getService(): SensorCollectionService = this@SensorCollectionService
    }

    override fun onCreate() {
        super.onCreate()
        imuSensorManager = ImuSensorManager(this)
        healthSensorManager = SensorManager(this)
        dataLogger = SessionDataLogger(this)
        hapticEngine = HapticEngine(this, serviceScope)
        iphoneBLE = IphoneBLECentral(this)

        // Forward haptic events to iPhone
        hapticEngine.setHapticEventListener { event ->
            dataLogger.logHaptic(event)
            iphoneBLE.writeHapticEvent(event.eventType, event.patternType)
        }

        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCollection(
                subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: "sub-001",
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: "sess-001",
                watchSide = intent.getStringExtra(EXTRA_WATCH_SIDE) ?: "left",
            )
            ACTION_STOP -> stopCollection()
            ACTION_EVENT -> intent.getStringExtra(EXTRA_EVENT_NAME)?.let { eventName ->
                logEventInternal(
                    eventName = eventName,
                    source = intent.getStringExtra(EXTRA_EVENT_SOURCE) ?: "manual",
                    notes = intent.getStringExtra(EXTRA_EVENT_NOTES) ?: "",
                )
            }
            ACTION_HAPTIC_TEST -> hapticEngine.measureLatency()
            ACTION_HAPTIC_GAIT -> {
                val cadence = intent.getFloatExtra(EXTRA_CADENCE, 100f)
                hapticEngine.startGaitMetronome(cadence)
            }
            ACTION_HAPTIC_BREATHING -> hapticEngine.startBreathingPacing()
            ACTION_HAPTIC_TREMOR -> hapticEngine.startTremorGrounding()
            ACTION_HAPTIC_STOP -> hapticEngine.stop()
        }
        return START_STICKY
    }

    /** Start sensor collection and logging for a new session */
    fun startCollection(
        subjectId: String,
        sessionId: String,
        watchSide: String = "left",
    ) {
        if (isCollecting) {
            Log.w(TAG, "Already collecting, ignoring start request")
            return
        }

        currentMetadata = SessionMetadata(
            subjectId = subjectId,
            sessionId = sessionId,
            watchSide = watchSide,
            sessionStartEpochMs = System.currentTimeMillis(),
        )

        dataLogger.startSession(currentMetadata!!)

        // Notify iPhone
        iphoneBLE.writeControl("session_start", subjectId, sessionId)

        // Start IMU collection at 50 Hz
        imuUnregister = imuSensorManager.registerListener(
            samplingPeriodUs = IMU_SAMPLING_PERIOD_US,
            onSample = { sample ->
                dataLogger.logImu(sample)
                // Forward to iPhone periodically (avoid BLE bandwidth saturation)
                imuSampleCounter++
                if (imuSampleCounter % BLE_FORWARD_INTERVAL == 0) {
                    iphoneBLE.writeImuSample(sample)
                }
            }
        )

        // Start HR collection via Health Services
        hrCollectionJob = serviceScope.launch {
            try {
                healthSensorManager.heartRateFlow().collect { hrSample ->
                    dataLogger.logHr(hrSample)
                    hrSampleCounter++
                    if (hrSampleCounter % BLE_FORWARD_INTERVAL == 0) {
                        iphoneBLE.writeHrSample(hrSample)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HR collection error", e)
            }
        }

        isCollecting = true
        startForeground(NOTIFICATION_ID, buildNotification("Recording sensors..."))
        Log.i(TAG, "Sensor collection started: $subjectId/$sessionId")
    }

    /** Stop sensor collection */
    fun stopCollection() {
        if (!isCollecting) return

        imuUnregister?.invoke()
        imuUnregister = null

        hrCollectionJob?.cancel()
        hrCollectionJob = null

        hapticEngine.stop()

        iphoneBLE.writeControl("session_end")

        serviceScope.launch(Dispatchers.IO) {
            dataLogger.endSession()
        }

        isCollecting = false
        currentMetadata = null
        imuSampleCounter = 0
        hrSampleCounter = 0

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Sensor collection stopped")
    }

    /** Log an event during the session */
    fun logEvent(eventName: String, source: String = "manual", notes: String = "") {
        logEventInternal(eventName, source, notes)
        iphoneBLE.writeEvent(eventName, source, notes)
    }

    private fun logEventInternal(eventName: String, source: String, notes: String) {
        dataLogger.logEvent(EventEntry(
            epochMs = System.currentTimeMillis(),
            eventName = eventName,
            source = source,
            notes = notes,
        ))
    }

    /** Check if currently collecting data */
    fun isCollecting(): Boolean = isCollecting

    /** Get current session metadata */
    fun getCurrentMetadata(): SessionMetadata? = currentMetadata

    /** Get sensor status for the debug screen */
    fun getSensorStatus(): Map<String, String> {
        return mapOf(
            "imu_available" to imuSensorManager.isAvailable.toString(),
            "collecting" to isCollecting.toString(),
            "has_vibrator" to hapticEngine.hasVibrator().toString(),
            "iphone_connected" to iphoneBLE.isIphoneConnected().toString(),
            "imu_samples" to imuSampleCounter.toString(),
        )
    }

    private fun buildNotification(text: String): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Daedalus Watch")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setCategory(Notification.CATEGORY_SERVICE)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daedalus Sensor Collection",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopCollection()
        iphoneBLE.disconnect()
        serviceScope.cancel()
        hapticEngine.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.medicina.daedaluswatch.ACTION_START"
        const val ACTION_STOP = "com.medicina.daedaluswatch.ACTION_STOP"
        const val ACTION_EVENT = "com.medicina.daedaluswatch.ACTION_EVENT"
        const val ACTION_HAPTIC_TEST = "com.medicina.daedaluswatch.ACTION_HAPTIC_TEST"
        const val ACTION_HAPTIC_GAIT = "com.medicina.daedaluswatch.ACTION_HAPTIC_GAIT"
        const val ACTION_HAPTIC_BREATHING = "com.medicina.daedaluswatch.ACTION_HAPTIC_BREATHING"
        const val ACTION_HAPTIC_TREMOR = "com.medicina.daedaluswatch.ACTION_HAPTIC_TREMOR"
        const val ACTION_HAPTIC_STOP = "com.medicina.daedaluswatch.ACTION_HAPTIC_STOP"

        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_WATCH_SIDE = "watch_side"
        const val EXTRA_EVENT_NAME = "event_name"
        const val EXTRA_EVENT_SOURCE = "event_source"
        const val EXTRA_EVENT_NOTES = "event_notes"
        const val EXTRA_CADENCE = "cadence"
    }
}
