package com.medicina.daedaluswatch.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.medicina.daedaluswatch.model.HapticEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "HapticEngine"

// Named constants (was magic numbers)
private const val DEFAULT_PULSE_DURATION_MS = 20L
private const val BREATHING_INHALE_PULSE_MS = 100L
private const val BREATHING_EXHALE_PULSE_MS = 50L
private const val TREMOR_PULSE_DURATION_MS = 15L
private const val TREMOR_PULSE_AMPLITUDE = 50
private const val BREATHING_INHALE_SEC = 4L
private const val BREATHING_EXHALE_SEC = 6L
private const val DEFAULT_TREMOR_FREQ_HZ = 2.5f
private const val DEFAULT_BREATHING_DURATION_SEC = 180L
private const val DEFAULT_GAIT_DURATION_SEC = 60L
private const val DEFAULT_TREMOR_DURATION_SEC = 60L

/**
 * Manages haptic feedback on the Samsung Galaxy Watch5 Pro.
 *
 * Supports:
 * - Gait metronome (rhythmic pulses at target cadence)
 * - Breathing pacing (long inhale cue, short exhale cue)
 * - Tremor grounding (gentle 2-3 Hz pulses)
 * - Latency testing (trigger → onset measurement)
 *
 * Target: trigger-to-onset latency < 150 ms, jitter < 30 ms.
 *
 * Uses Kotlin coroutines for cancellable, lifecycle-aware execution.
 */
class HapticEngine(
    context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val _hapticEvents = MutableSharedFlow<HapticEvent>(extraBufferCapacity = 64)
    val hapticEvents: SharedFlow<HapticEvent> = _hapticEvents.asSharedFlow()

    private var activeJob: Job? = null

    /** Set a listener for haptic events (alternative to collecting the flow) */
    fun setHapticEventListener(listener: (HapticEvent) -> Unit) {
        coroutineScope.launch {
            hapticEvents.collect { listener(it) }
        }
    }

    /** Single short pulse (generic tap) */
    fun triggerPulse(durationMs: Long = DEFAULT_PULSE_DURATION_MS) {
        val now = System.currentTimeMillis()
        emitEvent(HapticEvent(now, "trigger", "generic"))
        vibrate(durationMs)
        emitEvent(HapticEvent(now, "onset", "generic"))
    }

    /**
     * Gait metronome: rhythmic pulses at the target cadence.
     * Pulse interval = 60 / cadence_steps_per_min (in seconds).
     */
    fun startGaitMetronome(cadenceStepsPerMin: Float, durationSec: Long = DEFAULT_GAIT_DURATION_SEC) {
        val intervalMs = (60f / cadenceStepsPerMin * 1000).toLong()
        val pulseDuration = DEFAULT_PULSE_DURATION_MS

        activeJob?.cancel()
        activeJob = coroutineScope.launch {
            val endTime = System.currentTimeMillis() + durationSec * 1000
            emitEvent(HapticEvent(System.currentTimeMillis(), "trigger", "gait_metronome"))

            while (isActive && System.currentTimeMillis() < endTime) {
                val triggerTime = System.currentTimeMillis()
                emitEvent(HapticEvent(triggerTime, "trigger", "gait_metronome"))
                vibrate(pulseDuration)
                emitEvent(HapticEvent(triggerTime, "onset", "gait_metronome"))

                val sleepTime = intervalMs - pulseDuration
                if (sleepTime > 0) delay(sleepTime)
            }
            emitEvent(HapticEvent(System.currentTimeMillis(), "offset", "gait_metronome"))
        }
    }

    /**
     * Breathing pacing: inhale cue (longer) then exhale cue (shorter).
     * Default: 4 sec inhale, 6 sec exhale (6 breaths/min).
     */
    fun startBreathingPacing(
        inhaleSec: Long = BREATHING_INHALE_SEC,
        exhaleSec: Long = BREATHING_EXHALE_SEC,
        durationSec: Long = DEFAULT_BREATHING_DURATION_SEC,
    ) {
        activeJob?.cancel()
        activeJob = coroutineScope.launch {
            val endTime = System.currentTimeMillis() + durationSec * 1000
            emitEvent(HapticEvent(System.currentTimeMillis(), "trigger", "breathing_pacing"))

            while (isActive && System.currentTimeMillis() < endTime) {
                // Inhale cue: longer pulse
                val inhaleStart = System.currentTimeMillis()
                emitEvent(HapticEvent(inhaleStart, "trigger", "breathing_inhale"))
                vibrate(BREATHING_INHALE_PULSE_MS)
                emitEvent(HapticEvent(inhaleStart, "onset", "breathing_inhale"))
                delay(inhaleSec * 1000)

                // Exhale cue: shorter pulse
                val exhaleStart = System.currentTimeMillis()
                emitEvent(HapticEvent(exhaleStart, "trigger", "breathing_exhale"))
                vibrate(BREATHING_EXHALE_PULSE_MS)
                emitEvent(HapticEvent(exhaleStart, "onset", "breathing_exhale"))
                delay(exhaleSec * 1000)
            }
            emitEvent(HapticEvent(System.currentTimeMillis(), "offset", "breathing_pacing"))
        }
    }

    /**
     * Tremor grounding: gentle low-frequency pulses (2-3 Hz).
     * Exploratory only — not therapeutic validation.
     */
    fun startTremorGrounding(
        frequencyHz: Float = DEFAULT_TREMOR_FREQ_HZ,
        durationSec: Long = DEFAULT_TREMOR_DURATION_SEC,
    ) {
        val intervalMs = (1000f / frequencyHz).toLong()
        val pulseDuration = TREMOR_PULSE_DURATION_MS

        activeJob?.cancel()
        activeJob = coroutineScope.launch {
            val endTime = System.currentTimeMillis() + durationSec * 1000
            emitEvent(HapticEvent(System.currentTimeMillis(), "trigger", "tremor_grounding"))

            while (isActive && System.currentTimeMillis() < endTime) {
                val triggerTime = System.currentTimeMillis()
                emitEvent(HapticEvent(triggerTime, "trigger", "tremor_grounding"))
                vibrateTremor(pulseDuration)
                emitEvent(HapticEvent(triggerTime, "onset", "tremor_grounding"))

                val sleepTime = intervalMs - pulseDuration
                if (sleepTime > 0) delay(sleepTime)
            }
            emitEvent(HapticEvent(System.currentTimeMillis(), "offset", "tremor_grounding"))
        }
    }

    /** Stop any running haptic pattern */
    fun stop() {
        activeJob?.cancel()
        activeJob = null
        vibrator.cancel()
        emitEvent(HapticEvent(System.currentTimeMillis(), "offset", "all"))
    }

    /**
     * Measure haptic latency: trigger vibration and return the trigger timestamp.
     */
    fun measureLatency(): Long {
        val triggerTime = System.currentTimeMillis()
        emitEvent(HapticEvent(triggerTime, "trigger", "latency_test"))
        vibrate(DEFAULT_PULSE_DURATION_MS)
        emitEvent(HapticEvent(triggerTime, "onset", "latency_test"))
        return triggerTime
    }

    private fun vibrate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun vibrateTremor(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(durationMs, TREMOR_PULSE_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun emitEvent(event: HapticEvent) {
        // Non-blocking emission with buffer
        coroutineScope.launch {
            _hapticEvents.emit(event)
        }
    }

    fun hasVibrator(): Boolean = vibrator.hasVibrator()

    /** Clean up resources */
    fun onDestroy() {
        stop()
        coroutineScope.cancel()
    }
}
