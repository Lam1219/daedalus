package com.medicina.daedaluswatch.sensor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

private const val TAG = "VitalsTracker"

/**
 * Breathing rate and SpO2 from the Samsung Health Sensor SDK during a cue session.
 *
 * - Breathing rate: the continuous heart-rate tracker's beat-to-beat intervals feed
 *   [BreathingRateEstimator]; [onBreathingRate] gets a rolling 60 s estimate (null = unclear).
 * - SpO2: an on-demand reading (~30 s, wrist still) at the start and every [spo2EveryMs],
 *   only when [canMeasureSpo2] allows it. [onSpo2Measuring] brackets each reading so the cue
 *   can pause its pulses (vibration corrupts the optical signal); [onSpo2] fires once per
 *   completed reading.
 *
 * Needs Samsung Health developer mode on the watch while the app is unregistered with Samsung.
 */
class VitalsTracker(
    context: Context,
    private val onBreathingRate: (Float?) -> Unit,
    private val onSpo2: (Int) -> Unit,
    private val canMeasureSpo2: () -> Boolean = { true },
    private val onSpo2Measuring: (Boolean) -> Unit = {},
    private val spo2EveryMs: Long = 10 * 60_000L,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val estimator = BreathingRateEstimator()

    private var service: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var spo2Tracker: HealthTracker? = null
    private var running = false
    private var ibisReceived = 0
    private var ibisValid = 0
    private var hrPoints = 0

    fun start() {
        if (running) return
        running = true
        estimator.reset()
        service = HealthTrackingService(object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.i(TAG, "Connected to Samsung Health tracking service")
                if (running) startTrackers()
            }

            override fun onConnectionEnded() {
                Log.i(TAG, "Samsung Health tracking service disconnected")
            }

            override fun onConnectionFailed(e: HealthTrackerException) {
                Log.e(TAG, "Samsung Health connection failed (code ${e.errorCode}): ${e.message}")
            }
        }, appContext)
        runCatching { service?.connectService() }
            .onFailure { Log.e(TAG, "connectService failed: ${it.message}") }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (spo2Tracker != null) onSpo2Measuring(false)
        runCatching { hrTracker?.unsetEventListener() }
        runCatching { spo2Tracker?.unsetEventListener() }
        hrTracker = null
        spo2Tracker = null
        runCatching { service?.disconnectService() }
        service = null
    }

    private fun supported(type: HealthTrackerType): Boolean =
        runCatching { service?.trackingCapability?.supportHealthTrackerTypes?.contains(type) == true }.getOrDefault(false)

    private fun startTrackers() {
        handler.postDelayed(diagnostics, DIAGNOSTICS_EVERY_MS)
        // An SpO2 reading takes over the optical sensor, so the heart-rate stream starts after it.
        if (supported(HealthTrackerType.SPO2_ON_DEMAND) && canMeasureSpo2()) {
            handler.post(spo2Loop)
        } else {
            if (!supported(HealthTrackerType.SPO2_ON_DEMAND)) Log.w(TAG, "SPO2_ON_DEMAND not supported; SpO2 unavailable")
            startHeartRate()
        }
    }

    /** Continuous heart rate with beat-to-beat intervals, for the breathing-rate estimate. */
    private fun startHeartRate() {
        if (!running || hrTracker != null) return
        if (!supported(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
            Log.w(TAG, "HEART_RATE_CONTINUOUS not supported; breathing rate unavailable")
            return
        }
        // Start a fresh window: intervals from before a gap would be stitched to later ones.
        estimator.reset()
        hrTracker = service?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        hrTracker?.setEventListener(listener("heart rate") { points -> points.forEach(::onHeartRatePoint) })
        handler.postDelayed(flushHeartRate, FLUSH_EVERY_MS)
        Log.i(TAG, "Heart-rate stream started")
    }

    /** Samsung batches continuous heart-rate data on the watch; flushing delivers it promptly. */
    private val flushHeartRate = object : Runnable {
        override fun run() {
            val tracker = hrTracker ?: return
            if (!running) return
            runCatching { tracker.flush() }.onFailure { Log.w(TAG, "Heart-rate flush failed: ${it.message}") }
            handler.postDelayed(this, FLUSH_EVERY_MS)
        }
    }

    private fun stopHeartRate() {
        val tracker = hrTracker ?: return
        handler.removeCallbacks(flushHeartRate)
        runCatching { tracker.unsetEventListener() }
        hrTracker = null
        Log.i(TAG, "Heart-rate stream paused")
    }

    private val diagnostics = object : Runnable {
        override fun run() {
            if (!running) return
            Log.i(TAG, "Last ${DIAGNOSTICS_EVERY_MS / 1000}s: $hrPoints heart-rate points, $ibisReceived intervals received, $ibisValid valid; breathing estimate = ${estimator.estimate()}")
            ibisReceived = 0
            ibisValid = 0
            hrPoints = 0
            handler.postDelayed(this, DIAGNOSTICS_EVERY_MS)
        }
    }

    private fun onHeartRatePoint(point: DataPoint) {
        hrPoints++
        val ibis = point.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: return
        val statuses = point.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
        ibis.forEachIndexed { i, ibi ->
            ibisReceived++
            // Status 0 = valid interval.
            if (statuses == null || statuses.getOrNull(i) == 0) {
                ibisValid++
                estimator.addInterval(ibi.toFloat())
            }
        }
        onBreathingRate(estimator.estimate())
    }

    private val spo2Loop = object : Runnable {
        override fun run() {
            if (!running) return
            measureSpo2()
            handler.postDelayed(this, spo2EveryMs)
        }
    }

    private fun measureSpo2() {
        if (!canMeasureSpo2()) {
            Log.i(TAG, "SpO2 skipped: cue must keep vibrating")
            startHeartRate()
            return
        }
        val tracker = service?.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND) ?: run { startHeartRate(); return }
        stopHeartRate()
        spo2Tracker = tracker
        onSpo2Measuring(true)

        lateinit var finish: (String) -> Unit
        val timeout = Runnable { finish("timed out: no stable signal (motion or poor skin contact)") }
        finish = { reason ->
            if (spo2Tracker === tracker) {
                Log.i(TAG, "SpO2 reading finished: $reason")
                handler.removeCallbacks(timeout)
                runCatching { tracker.unsetEventListener() }
                spo2Tracker = null
                onSpo2Measuring(false)
                startHeartRate()
            }
        }
        tracker.setEventListener(listener("SpO2", onFailed = { handler.post { finish("tracker error") } }) { points ->
            points.forEach { point ->
                if (point.getValue(ValueKey.SpO2Set.STATUS) == SPO2_MEASUREMENT_COMPLETED) {
                    val spo2 = point.getValue(ValueKey.SpO2Set.SPO2)
                    if (spo2 != null && spo2 in 70..100) {
                        Log.i(TAG, "SpO2 reading: $spo2%")
                        onSpo2(spo2)
                    }
                    handler.post { finish("completed") }
                }
            }
        })
        handler.postDelayed(timeout, SPO2_TIMEOUT_MS)
    }

    private fun listener(name: String, onFailed: () -> Unit = {}, onData: (List<DataPoint>) -> Unit) = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            if (running) onData(points)
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            // PERMISSION_ERROR = sensor permission missing; SDK_POLICY_ERROR = developer mode off / app not approved.
            Log.e(TAG, "$name tracker error: $error")
            onFailed()
        }
    }

    private companion object {
        /** SpO2Set.STATUS value for a finished, valid measurement. */
        const val SPO2_MEASUREMENT_COMPLETED = 2
        const val SPO2_TIMEOUT_MS = 40_000L
        const val DIAGNOSTICS_EVERY_MS = 30_000L
        const val FLUSH_EVERY_MS = 15_000L
    }
}
