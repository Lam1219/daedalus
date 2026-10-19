package com.medicina.daedaluswatch.sensor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Estimates breathing rate from beat-to-beat (inter-beat) intervals.
 *
 * Heart rate rises slightly on each inhale and falls on each exhale (respiratory sinus
 * arrhythmia), so the dominant rhythm of the interval series between 0.1 and 0.5 Hz
 * (6–30 breaths per minute) is the breathing rate. Feed every interval as it arrives;
 * [estimate] returns null until there is enough clean data.
 */
class BreathingRateEstimator(
    private val windowMs: Long = 60_000,
    private val minWindowMs: Long = 30_000,
    private val minBeats: Int = 30,
) {
    private data class Beat(val timeMs: Long, val ibiMs: Float)

    private val beats = ArrayDeque<Beat>()
    private var clock = 0L

    /** Adds one interval (ms). Implausible intervals and sudden jumps (ectopic beats) are skipped. */
    fun addInterval(ibiMs: Float) {
        if (ibiMs < 300f || ibiMs > 2000f) return
        val previous = beats.lastOrNull()
        clock += ibiMs.toLong()
        if (previous != null && abs(ibiMs - previous.ibiMs) / previous.ibiMs > 0.2f) return
        beats.addLast(Beat(clock, ibiMs))
        while (beats.isNotEmpty() && clock - beats.first().timeMs > windowMs) beats.removeFirst()
    }

    fun reset() {
        beats.clear()
        clock = 0L
    }

    /** Breaths per minute, or null when the window is too short or has no clear breathing rhythm. */
    fun estimate(): Float? {
        if (beats.size < minBeats) return null
        val start = beats.first().timeMs
        val end = beats.last().timeMs
        if (end - start < minWindowMs) return null

        // Resample the interval series evenly at 4 Hz.
        val stepMs = 250L
        val samples = ArrayList<Double>()
        var t = start
        var i = 0
        val list = beats.toList()
        while (t <= end) {
            while (i < list.size - 2 && list[i + 1].timeMs < t) i++
            val a = list[i]
            val b = list[i + 1]
            val f = if (b.timeMs == a.timeMs) 0.0 else (t - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
            samples.add(a.ibiMs + f * (b.ibiMs - a.ibiMs))
            t += stepMs
        }
        val mean = samples.average()
        val x = samples.map { it - mean }
        val fs = 1000.0 / stepMs

        // Power across the breathing band; pick the strongest frequency.
        var bestHz = 0.0
        var bestPower = 0.0
        var totalPower = 0.0
        var hz = 0.10
        while (hz <= 0.50 + 1e-9) {
            var re = 0.0
            var im = 0.0
            for (n in x.indices) {
                val angle = 2 * PI * hz * n / fs
                re += x[n] * cos(angle)
                im -= x[n] * sin(angle)
            }
            val power = re * re + im * im
            totalPower += power
            if (power > bestPower) {
                bestPower = power
                bestHz = hz
            }
            hz += 0.005
        }
        // No clear peak (flat spectrum) = no reliable estimate.
        val bins = ((0.50 - 0.10) / 0.005).toInt() + 1
        if (totalPower <= 0.0 || bestPower < 3 * (totalPower / bins)) return null
        return (bestHz * 60).toFloat()
    }
}
