package com.medicina.daedaluscompanion.data

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tremor check (model "tremor-1.0"): finds the rhythm and strength of a resting tremor in about
 * 20 seconds of wrist rotation (gyroscope, rad/s) recorded with the hand at rest.
 *
 * The same file is in the Galaxy Watch app (shows the result right away) and the Android phone app
 * (reads it back from the session file for the Journal); TremorAnalyzer.swift is its iOS twin.
 * Change all three together and bump [VERSION].
 *
 * Steps: drop the first second (the start buzz), resample evenly at 50 Hz, remove each axis's
 * mean, apply a Hann window, then sum the three axes' power spectra from 0.5 to 12 Hz.
 * - Too much slow movement (0.5–2.5 Hz) → [Outcome.MOVED].
 * - A clear peak in the tremor band (3–8 Hz) with enough power → [Outcome.TREMOR], with its
 *   frequency and a strength level from the band's RMS rotation speed.
 * - Otherwise → [Outcome.NONE].
 *
 * Thresholds are provisional: they are to be set in the healthy-volunteer study. This is a
 * wellness trend, never a diagnosis.
 */
object TremorAnalyzer {
    const val VERSION = "tremor-1.0"
    const val MODE = "tremor"
    /** How long the watch records for one check. */
    const val CHECK_DURATION_MS = 20_000L

    private const val SETTLE_MS = 1_000L
    private const val FS = 50.0
    private const val MIN_DURATION_S = 10.0
    private const val MAX_GAP_MS = 250L
    private const val F_MIN = 0.5
    private const val F_MAX = 12.0
    private const val DF = 0.05
    private const val BAND_LOW = 3.0
    private const val BAND_HIGH = 8.0
    private const val SLOW_HIGH = 2.5
    private const val PEAK_EXCLUDE_HZ = 1.0
    private const val RAD_TO_DEG = 180.0 / PI

    /** Thresholds in degrees per second (RMS). */
    const val MIN_TREMOR_DPS = 1.5
    const val MODERATE_DPS = 10.0
    const val STRONG_DPS = 40.0
    const val MAX_SLOW_DPS = 20.0
    /** Peak power over the mean power elsewhere in 0.5–12 Hz. */
    const val MIN_PROMINENCE = 6.0

    enum class Outcome { TREMOR, NONE, MOVED, INCOMPLETE }
    enum class Strength { LIGHT, MODERATE, STRONG }

    data class Result(
        val outcome: Outcome,
        /** Peak frequency in Hz, to one decimal; only for [Outcome.TREMOR]. */
        val frequencyHz: Double? = null,
        val strength: Strength? = null,
        /** RMS rotation speed in the 3–8 Hz band, degrees per second. */
        val bandDps: Double = 0.0,
        val prominence: Double = 0.0,
        val version: String = VERSION,
    )

    /**
     * [timesMs] wall-clock milliseconds, increasing; [gx], [gy], [gz] rotation rate in rad/s.
     * Samples need not be evenly spaced.
     */
    fun analyze(timesMs: LongArray, gx: DoubleArray, gy: DoubleArray, gz: DoubleArray): Result {
        val n0 = minOf(timesMs.size, gx.size, gy.size, gz.size)
        if (n0 < 2) return Result(Outcome.INCOMPLETE)
        val start = timesMs[0] + SETTLE_MS
        val end = timesMs[n0 - 1]
        val durationS = (end - start) / 1000.0
        if (durationS < MIN_DURATION_S) return Result(Outcome.INCOMPLETE)
        for (i in 1 until n0) if (timesMs[i] - timesMs[i - 1] > MAX_GAP_MS && timesMs[i] > start) return Result(Outcome.INCOMPLETE)

        // Resample each axis evenly at 50 Hz (linear interpolation).
        val n = (durationS * FS).toInt() + 1
        val axes = listOf(gx, gy, gz).map { DoubleArray(n) }
        var j = 0
        for (k in 0 until n) {
            val t = start + k * 1000.0 / FS
            while (j < n0 - 2 && timesMs[j + 1] < t) j++
            val t0 = timesMs[j].toDouble()
            val t1 = timesMs[j + 1].toDouble()
            val f = if (t1 == t0) 0.0 else ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
            axes[0][k] = gx[j] + f * (gx[j + 1] - gx[j])
            axes[1][k] = gy[j] + f * (gy[j + 1] - gy[j])
            axes[2][k] = gz[j] + f * (gz[j + 1] - gz[j])
        }

        // Hann window, then the one-sided power spectral density summed over the three axes
        // (Spectrum, shared with the streaming features); band power is in (rad/s)².
        val window = Spectrum.hann(n)
        val signals = Spectrum.prepare(axes, window)
        val bins = ((F_MAX - F_MIN) / DF).roundToInt() + 1
        val freqs = DoubleArray(bins) { F_MIN + it * DF }
        val psd = Spectrum.psd(signals, FS, window, freqs)

        fun bandPower(low: Double, high: Double): Double {
            var p = 0.0
            for (b in 0 until bins) if (freqs[b] >= low - 1e-9 && freqs[b] <= high + 1e-9) p += psd[b] * DF
            return p
        }

        val slowDps = sqrt(bandPower(F_MIN, SLOW_HIGH)) * RAD_TO_DEG
        val bandDps = sqrt(bandPower(BAND_LOW, BAND_HIGH)) * RAD_TO_DEG

        var peak = -1
        for (b in 0 until bins) {
            if (freqs[b] < BAND_LOW - 1e-9 || freqs[b] > BAND_HIGH + 1e-9) continue
            if (peak < 0 || psd[b] > psd[peak]) peak = b
        }
        var restSum = 0.0
        var restCount = 0
        for (b in 0 until bins) {
            if (kotlin.math.abs(freqs[b] - freqs[peak]) <= PEAK_EXCLUDE_HZ) continue
            restSum += psd[b]
            restCount++
        }
        val prominence = if (restCount == 0 || restSum <= 0.0) 0.0 else psd[peak] / (restSum / restCount)

        if (slowDps > MAX_SLOW_DPS) return Result(Outcome.MOVED, bandDps = bandDps, prominence = prominence)
        if (bandDps < MIN_TREMOR_DPS || prominence < MIN_PROMINENCE) {
            return Result(Outcome.NONE, bandDps = bandDps, prominence = prominence)
        }

        // Parabolic interpolation around the peak for a finer frequency.
        var hz = freqs[peak]
        if (peak in 1 until bins - 1) {
            val a = psd[peak - 1]
            val b = psd[peak]
            val c = psd[peak + 1]
            val denom = a - 2 * b + c
            if (denom != 0.0) hz += (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) * DF
        }
        val strength = when {
            bandDps >= STRONG_DPS -> Strength.STRONG
            bandDps >= MODERATE_DPS -> Strength.MODERATE
            else -> Strength.LIGHT
        }
        return Result(Outcome.TREMOR, (hz * 10).roundToInt() / 10.0, strength, bandDps, prominence)
    }

    /**
     * Analyses a session CSV (columns `timestamp_ms` and `gx,gy,gz`, as both watches write them).
     * Rows that don't parse are skipped.
     */
    fun analyzeCsv(text: String): Result {
        val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return Result(Outcome.INCOMPLETE)
        val header = lines.next().split(",").map { it.trim() }
        val iT = header.indexOf("timestamp_ms")
        val iX = header.indexOf("gx")
        val iY = header.indexOf("gy")
        val iZ = header.indexOf("gz")
        if (iT < 0 || iX < 0 || iY < 0 || iZ < 0) return Result(Outcome.INCOMPLETE)
        val t = ArrayList<Long>()
        val x = ArrayList<Double>()
        val y = ArrayList<Double>()
        val z = ArrayList<Double>()
        val need = maxOf(iT, iX, iY, iZ)
        for (line in lines) {
            val cells = line.split(",")
            if (cells.size <= need) continue
            val time = cells[iT].trim().toLongOrNull() ?: continue
            val vx = cells[iX].trim().toDoubleOrNull() ?: continue
            val vy = cells[iY].trim().toDoubleOrNull() ?: continue
            val vz = cells[iZ].trim().toDoubleOrNull() ?: continue
            // Keep times strictly increasing (two rows in the same millisecond add nothing).
            if (t.isNotEmpty() && time <= t.last()) continue
            t.add(time); x.add(vx); y.add(vy); z.add(vz)
        }
        return analyze(t.toLongArray(), x.toDoubleArray(), y.toDoubleArray(), z.toDoubleArray())
    }
}
