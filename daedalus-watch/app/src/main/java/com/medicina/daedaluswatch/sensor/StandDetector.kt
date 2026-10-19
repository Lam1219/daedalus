package com.medicina.daedaluswatch.sensor

import kotlin.math.sqrt

/**
 * Detects a sit-to-stand from the wrist: the watch rises (air pressure drops by about
 * 0.12 hPa per metre) while the body moves, after a period of sitting fairly still.
 *
 * Heuristic and experimental: a large arm raise after sitting still can look the same,
 * so thresholds are conservative and a cooldown prevents repeated prompts.
 * Feed every sensor sample with its timestamp; [onPressure] returns true at the moment
 * a stand-up is detected.
 */
class StandDetector(
    /** Minimum wrist rise, as pressure drop (hPa). 0.025 hPa ≈ 21 cm. */
    private val minDropHpa: Float = 0.025f,
    /** The rise must happen within this window. */
    private val riseWindowMs: Long = 4_000,
    /** Sitting fairly still for this long before the rise. */
    private val stillBeforeMs: Long = 8_000,
    /** Accelerometer std-dev (m/s²) under which the wrist counts as still. */
    private val stillStd: Float = 1.2f,
    /** Accelerometer std-dev (m/s²) the rise itself must exceed (the body moves). */
    private val moveStd: Float = 0.6f,
    /** No new prompt within this time after the last one. */
    private val cooldownMs: Long = 90_000,
) {
    private data class Sample(val t: Long, val v: Float)

    private val pressure = ArrayDeque<Sample>()
    private val accel = ArrayDeque<Sample>()
    private var smoothed: Float? = null
    private var lastDetection = Long.MIN_VALUE / 2

    /** Accelerometer magnitude sample (m/s²). */
    fun onAccel(tMs: Long, x: Float, y: Float, z: Float) {
        accel.addLast(Sample(tMs, sqrt(x * x + y * y + z * z)))
        trim(accel, tMs - (stillBeforeMs + riseWindowMs + 2_000))
    }

    /** Barometer sample (hPa). Returns true when a stand-up is detected now. */
    fun onPressure(tMs: Long, hPa: Float): Boolean {
        // Light smoothing: barometer noise is ~0.01 hPa per sample.
        val s = smoothed?.let { it + 0.3f * (hPa - it) } ?: hPa
        smoothed = s
        pressure.addLast(Sample(tMs, s))
        trim(pressure, tMs - (riseWindowMs + 6_000))

        if (tMs - lastDetection < cooldownMs) return false
        val windowStart = tMs - riseWindowMs
        val before = pressure.filter { it.t in (windowStart - 2_000)..windowStart }
        if (before.size < 3) return false
        val baseline = before.map { it.v }.average().toFloat()
        val drop = baseline - s
        if (drop < minDropHpa) return false

        // The body moved during the rise…
        val riseAccel = accel.filter { it.t in windowStart..tMs }.map { it.v }
        if (riseAccel.size < 5 || std(riseAccel) < moveStd) return false
        // …after sitting fairly still.
        val stillAccel = accel.filter { it.t in (windowStart - stillBeforeMs) until windowStart }.map { it.v }
        if (stillAccel.size < 10 || std(stillAccel) > stillStd) return false

        lastDetection = tMs
        return true
    }

    private fun trim(q: ArrayDeque<Sample>, before: Long) {
        while (q.isNotEmpty() && q.first().t < before) q.removeFirst()
    }

    private fun std(values: List<Float>): Float {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).toFloat()
    }
}
