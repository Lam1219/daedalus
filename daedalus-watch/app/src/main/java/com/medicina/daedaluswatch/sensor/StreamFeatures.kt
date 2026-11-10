package com.medicina.daedaluswatch.sensor

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Streaming motion features for automatic cues (stream-features-1.0, Documentation/
 * automatic-cues-design.md 2.3). Every second a window is computed from the latest 50 Hz samples;
 * [TremorDetector] and [FogDetector] read these windows.
 *
 * Port of daedalus_watch_kit/stream_features.py, the reference; StreamFeatures.swift is the Apple
 * Watch twin. The three must give the same numbers on the same samples (checked by the golden
 * vectors in daedalus_watch_kit/golden, GoldenVectorsTest). Change all copies together, bump
 * [StreamFeatures.VERSION] and regenerate the golden files.
 *
 * 2 s window (100 samples), tremor and freeze index:
 * - tremorDps: RMS rotation in 3–8 Hz, deg/s (gyroscope, three axes summed)
 * - peakHz: frequency of the highest gyroscope power in 3–8 Hz, parabolic interpolation
 * - prominence: power at the peak / mean power over 0.5–12 Hz away from the peak (> 1 Hz)
 * - slowDps: RMS rotation in 0.5–2.5 Hz (voluntary movement); motionDps: 0.5–12 Hz
 * - meanRotationDps: size of the mean rotation vector (whole-body turning)
 * - accStd: population std of the acceleration magnitude, m/s²
 * - gravityChangeDeg: angle between this window's mean acceleration direction and the previous one's
 * - freezeIndex: acceleration-magnitude power 3.5–8 Hz / 0.5–3 Hz (Moore 2008)
 *
 * 4 s window (200 samples), gait; 0 until 4 s of data exist:
 * - cadenceSpm, stepRegularity: from the stride period (autocorrelation of the acceleration magnitude)
 * - armSwingDps: RMS rotation in 0.5–2 Hz
 *
 * Input units are the session units: acceleration in m/s² with gravity, rotation in rad/s, evenly
 * spaced at 50 Hz ([StreamFeatures.resampleUniform] first if not). Every feature is axis-free
 * (magnitudes, sums over axes, angles), because wrist axes differ between watches and wrists.
 * Pure Kotlin (no Android imports) so the JVM unit tests run it.
 */
object StreamFeatures {
    const val VERSION = "stream-features-1.0"

    const val FS = 50.0
    /** Samples between windows, 1 s. */
    const val HOP = 50
    /** Tremor and freeze-index window, 2 s. */
    const val WIN = 100
    /** Gait window, 4 s. */
    const val GAIT_WIN = 200
    const val RAD_TO_DEG = 180.0 / PI

    /** Stride (two steps) period search range: 35–70 strides/min = 70–140 steps/min. */
    const val STRIDE_LAG_MIN = 43   // round(FS * 60 / 70)
    const val STRIDE_LAG_MAX = 86   // round(FS * 60 / 35)

    // Context thresholds (provisional; tuned on Parkinson@Home and the volunteer recordings).
    const val WALK_REGULARITY = 0.5
    const val WALK_ACC_STD = 0.5            // m/s²
    /** Walking puts most acceleration power below 3 Hz; trembling doesn't. */
    const val WALK_MAX_FREEZE_INDEX = 1.0
    const val STILL_MOTION_DPS = 3.0
    const val STILL_ACC_STD = 0.15          // m/s²
    /** Same limit as tremor-1.0's "moved" rule. */
    const val REST_SLOW_DPS = 20.0
    const val POSTURE_CHANGE_DEG = 15.0
    const val TURN_MEAN_ROTATION_DPS = 25.0

    private const val EDGE = 1e-9

    private val HANN_WIN = Spectrum.hann(WIN)
    private val HANN_GAIT = Spectrum.hann(GAIT_WIN)
    /** rfft bin frequencies, k · FS / n for k = 0..n/2. */
    private val FREQS_WIN = DoubleArray(WIN / 2 + 1) { it * FS / WIN }
    private val FREQS_GAIT = DoubleArray(GAIT_WIN / 2 + 1) { it * FS / GAIT_WIN }

    class Resampled(val tMs: LongArray, val channels: List<DoubleArray>)

    /**
     * Linear interpolation of each channel onto an even [fs] grid from tMs[0] (numpy.interp:
     * clamped at the ends). Grid times are truncated to whole milliseconds.
     */
    fun resampleUniform(tMs: LongArray, vararg channels: DoubleArray, fs: Double = FS): Resampled {
        val xp = DoubleArray(tMs.size) { tMs[it].toDouble() }
        val t0 = xp[0]
        val n = ((xp[xp.size - 1] - t0) / 1000.0 * fs).toInt() + 1
        val grid = DoubleArray(n) { t0 + it * 1000.0 / fs }
        val out = channels.map { fp ->
            val y = DoubleArray(n)
            var j = 0
            for (i in 0 until n) {
                val x = grid[i]
                while (j + 1 < xp.size && xp[j + 1] <= x) j++
                y[i] = when {
                    x < xp[0] -> fp[0]
                    j == xp.size - 1 -> fp[j]
                    xp[j] == x -> fp[j]
                    else -> (fp[j + 1] - fp[j]) / (xp[j + 1] - xp[j]) * (x - xp[j]) + fp[j]
                }
            }
            y
        }
        return Resampled(LongArray(n) { grid[it].toLong() }, out)
    }

    /** Sum of psd over [low, high] Hz (inclusive, 1e-9 slack) times the bin width. */
    private fun band(freqs: DoubleArray, psd: DoubleArray, low: Double, high: Double): Double {
        var s = 0.0
        for (k in freqs.indices) if (freqs[k] >= low - EDGE && freqs[k] <= high + EDGE) s += psd[k]
        return s * (freqs[1] - freqs[0])
    }

    /** Normalised autocorrelation of a demeaned signal at one lag. */
    private fun autocorr(x: DoubleArray, lag: Int): Double {
        var aa = 0.0
        var bb = 0.0
        var ab = 0.0
        for (i in 0 until x.size - lag) {
            val a = x[i]
            val b = x[i + lag]
            aa += a * a
            bb += b * b
            ab += a * b
        }
        val den = sqrt(aa * bb)
        return if (den > 0) ab / den else 0.0
    }

    private fun magnitude(acc: Array<DoubleArray>): DoubleArray =
        DoubleArray(acc[0].size) { sqrt(acc[0][it] * acc[0][it] + acc[1][it] * acc[1][it] + acc[2][it] * acc[2][it]) }

    private fun gyroPsd(gyr: Array<DoubleArray>, window: DoubleArray, freqs: DoubleArray): DoubleArray =
        Spectrum.psd(Spectrum.prepare(gyr.toList(), window), FS, window, freqs)

    /**
     * Features of one hop. [acc2] and [gyr2] are three axes of the last 100 samples; [acc4] and
     * [gyr4] the last 200, or null before 4 s of data exist. Returns the features and this
     * window's gravity direction, passed back as [prevGravity] next time.
     *
     * [tremorWindowS] = 4 takes tremorDps, peakHz and prominence from the last 4 s (0.25 Hz bins,
     * for weak tremor) once 4 s exist; the context features stay on 2 s.
     */
    fun windowFeatures(
        acc2: Array<DoubleArray>, gyr2: Array<DoubleArray>,
        acc4: Array<DoubleArray>?, gyr4: Array<DoubleArray>?,
        prevGravity: DoubleArray?, tEndMs: Long, tremorWindowS: Int = 2,
    ): Pair<WindowFeatures, DoubleArray> {
        require(tremorWindowS == 2 || tremorWindowS == 4) { "tremorWindowS must be 2 or 4" }

        // Gyroscope spectrum over 2 s, three axes summed: voluntary movement and context.
        val p2 = gyroPsd(gyr2, HANN_WIN, FREQS_WIN)
        val slowDps = sqrt(band(FREQS_WIN, p2, 0.5, 2.5)) * RAD_TO_DEG
        val motionDps = sqrt(band(FREQS_WIN, p2, 0.5, 12.0)) * RAD_TO_DEG

        // Gyroscope spectrum over 4 s: arm swing, and the tremor when tremorWindowS = 4.
        val p4 = gyr4?.let { gyroPsd(it, HANN_GAIT, FREQS_GAIT) }

        // Tremor spectrum: the same 2 s, or the last 4 s.
        val useGait = tremorWindowS == 4 && p4 != null
        val f = if (useGait) FREQS_GAIT else FREQS_WIN
        val p = if (useGait) p4!! else p2
        val tremorDps = sqrt(band(f, p, 3.0, 8.0)) * RAD_TO_DEG

        var k = -1
        for (i in f.indices) {
            if (f[i] >= 3.0 - EDGE && f[i] <= 8.0 + EDGE && (k < 0 || p[i] > p[k])) k = i
        }
        val df = f[1] - f[0]
        var peakHz = f[k]
        if (k > 0 && k < p.size - 1) {
            val a = p[k - 1]
            val b = p[k]
            val c = p[k + 1]
            val den = a - 2 * b + c
            if (den != 0.0) peakHz += (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) * df
        }
        var restSum = 0.0
        var restCount = 0
        for (i in f.indices) {
            if (f[i] >= 0.5 - EDGE && f[i] <= 12.0 + EDGE && abs(f[i] - f[k]) > 1.0 + EDGE) {
                restSum += p[i]
                restCount++
            }
        }
        val meanRest = if (restCount > 0) restSum / restCount else 0.0
        val prominence = if (meanRest > 0) p[k] / meanRest else 0.0

        val mx = gyr2[0].average()
        val my = gyr2[1].average()
        val mz = gyr2[2].average()
        val meanRotationDps = sqrt(mx * mx + my * my + mz * mz) * RAD_TO_DEG

        // Acceleration magnitude: steps, freeze index, stillness.
        val mag2 = magnitude(acc2)
        val magMean = mag2.average()
        var ss = 0.0
        for (v in mag2) ss += (v - magMean) * (v - magMean)
        val accStd = sqrt(ss / mag2.size)
        val pa = Spectrum.psd(Spectrum.prepare(listOf(mag2), HANN_WIN), FS, HANN_WIN, FREQS_WIN)
        val low = band(FREQS_WIN, pa, 0.5, 3.0)
        // Bins are 0.5 Hz apart: 3.5–8 Hz, so 3.0 Hz is not counted twice.
        val high = band(FREQS_WIN, pa, 3.0 + 0.5, 8.0)
        val freezeIndex = if (low > 0) high / low else 0.0

        var gravity = doubleArrayOf(acc2[0].average(), acc2[1].average(), acc2[2].average())
        val norm = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (norm > 0) gravity = DoubleArray(3) { gravity[it] / norm }
        val gravityChangeDeg = if (prevGravity == null) 0.0 else {
            val dot = gravity[0] * prevGravity[0] + gravity[1] * prevGravity[1] + gravity[2] * prevGravity[2]
            acos(dot.coerceIn(-1.0, 1.0)) * RAD_TO_DEG
        }

        // Gait window, 4 s.
        var cadenceSpm = 0.0
        var stepRegularity = 0.0
        var armSwingDps = 0.0
        if (acc4 != null && p4 != null) {
            val mag4 = magnitude(acc4)
            val m4 = mag4.average()
            for (i in mag4.indices) mag4[i] -= m4
            var bestLag = 0
            var bestR = -1.0
            for (lag in STRIDE_LAG_MIN..STRIDE_LAG_MAX) {
                val r = autocorr(mag4, lag)
                if (r > bestR) {
                    bestLag = lag
                    bestR = r
                }
            }
            stepRegularity = maxOf(bestR, 0.0)
            cadenceSpm = if (bestLag != 0) 2 * 60.0 * FS / bestLag else 0.0
            armSwingDps = sqrt(band(FREQS_GAIT, p4, 0.5, 2.0)) * RAD_TO_DEG
        }

        val feats = WindowFeatures(
            tEndMs = tEndMs, tremorDps = tremorDps, peakHz = peakHz, prominence = prominence,
            slowDps = slowDps, motionDps = motionDps, meanRotationDps = meanRotationDps,
            accStd = accStd, gravityChangeDeg = gravityChangeDeg, freezeIndex = freezeIndex,
            cadenceSpm = cadenceSpm, stepRegularity = stepRegularity, armSwingDps = armSwingDps,
            context = Context.STILL,
        )
        return feats.copy(context = classifyContext(feats)) to gravity
    }

    /** Rule-based context, in priority order: walking, posture change, still, rest, active. */
    fun classifyContext(f: WindowFeatures): Context {
        // A steady 3–8 Hz tremble also repeats at stride lags, so regularity alone isn't walking.
        val walking = f.stepRegularity >= WALK_REGULARITY && f.accStd >= WALK_ACC_STD &&
            f.freezeIndex < WALK_MAX_FREEZE_INDEX
        if (walking) return if (f.meanRotationDps >= TURN_MEAN_ROTATION_DPS) Context.TURNING else Context.WALKING
        if (f.gravityChangeDeg >= POSTURE_CHANGE_DEG) return Context.ACTIVE_HAND
        if (f.motionDps < STILL_MOTION_DPS && f.accStd < STILL_ACC_STD) return Context.STILL
        if (f.slowDps < REST_SLOW_DPS) return Context.REST_TREMOR_POSSIBLE
        return Context.ACTIVE_HAND
    }

    /**
     * All windows of an evenly sampled 50 Hz recording; [acc] and [gyr] are three axes each.
     * Same windows as pushing every sample through [StreamFeatureExtractor].
     */
    fun computeFeatures(
        tMs: LongArray, acc: Array<DoubleArray>, gyr: Array<DoubleArray>, tremorWindowS: Int = 2,
    ): List<WindowFeatures> {
        val n = acc[0].size
        val out = ArrayList<WindowFeatures>()
        var prev: DoubleArray? = null
        var end = WIN
        while (end <= n) {
            val g = end >= GAIT_WIN
            val (f, gravity) = windowFeatures(
                Array(3) { acc[it].copyOfRange(end - WIN, end) },
                Array(3) { gyr[it].copyOfRange(end - WIN, end) },
                if (g) Array(3) { acc[it].copyOfRange(end - GAIT_WIN, end) } else null,
                if (g) Array(3) { gyr[it].copyOfRange(end - GAIT_WIN, end) } else null,
                prev, tMs[end - 1], tremorWindowS,
            )
            prev = gravity
            out.add(f)
            end += HOP
        }
        return out
    }

    // numpy details shared by the detectors.

    /** numpy.median: the middle value, or the mean of the middle two. */
    internal fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** Python round(x, digits): correctly rounded, ties to even. */
    internal fun pyRound(x: Double, digits: Int): Double =
        if (x.isNaN() || x.isInfinite()) x else BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
}

/** Motion context of one window; [value] is the Python string. */
enum class Context(val value: String) {
    STILL("still"),
    /** Hand at rest or holding a posture: tremor can be judged. */
    REST_TREMOR_POSSIBLE("rest"),
    /** Voluntary hand or arm movement, or a posture change. */
    ACTIVE_HAND("active"),
    WALKING("walking"),
    /** Walking and turning. */
    TURNING("turning"),
}

/** One 1 s hop of [StreamFeatures]; tEndMs is the time of the window's last sample. */
data class WindowFeatures(
    val tEndMs: Long,
    val tremorDps: Double,
    val peakHz: Double,
    val prominence: Double,
    val slowDps: Double,
    val motionDps: Double,
    val meanRotationDps: Double,
    val accStd: Double,
    val gravityChangeDeg: Double,
    val freezeIndex: Double,
    val cadenceSpm: Double,
    val stepRegularity: Double,
    val armSwingDps: Double,
    val context: Context,
)

/**
 * Push evenly spaced 50 Hz samples one at a time; a [WindowFeatures] comes out every second, as on
 * the watch. The first window comes once 2 s of samples exist; gait features stay 0 until 4 s.
 *
 * Samples go into a 4 s ring buffer: [push] allocates nothing. Once per hop the ring is copied
 * into reused linear buffers and the window is computed.
 */
class StreamFeatureExtractor(private val tremorWindowS: Int = 2) {
    init {
        require(tremorWindowS == 2 || tremorWindowS == 4) { "tremorWindowS must be 2 or 4" }
    }

    private val accRing = Array(3) { DoubleArray(StreamFeatures.GAIT_WIN) }
    private val gyrRing = Array(3) { DoubleArray(StreamFeatures.GAIT_WIN) }
    private val acc4 = Array(3) { DoubleArray(StreamFeatures.GAIT_WIN) }
    private val gyr4 = Array(3) { DoubleArray(StreamFeatures.GAIT_WIN) }
    private val acc2 = Array(3) { DoubleArray(StreamFeatures.WIN) }
    private val gyr2 = Array(3) { DoubleArray(StreamFeatures.WIN) }
    /** Next write position in the ring. */
    private var head = 0
    private var n = 0L
    private var prevGravity: DoubleArray? = null

    /** Acceleration in m/s² with gravity, rotation in rad/s. Returns a window once per second. */
    fun push(tMs: Long, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): WindowFeatures? {
        accRing[0][head] = ax
        accRing[1][head] = ay
        accRing[2][head] = az
        gyrRing[0][head] = gx
        gyrRing[1][head] = gy
        gyrRing[2][head] = gz
        head = (head + 1) % StreamFeatures.GAIT_WIN
        n++
        if (n < StreamFeatures.WIN || (n - StreamFeatures.WIN) % StreamFeatures.HOP != 0L) return null

        // Unroll the ring, oldest first.
        val size = StreamFeatures.GAIT_WIN
        val off = size - StreamFeatures.WIN
        for (axis in 0 until 3) {
            for (i in 0 until size) {
                val j = (head + i) % size
                acc4[axis][i] = accRing[axis][j]
                gyr4[axis][i] = gyrRing[axis][j]
            }
            System.arraycopy(acc4[axis], off, acc2[axis], 0, StreamFeatures.WIN)
            System.arraycopy(gyr4[axis], off, gyr2[axis], 0, StreamFeatures.WIN)
        }
        val full = n >= StreamFeatures.GAIT_WIN
        val (feats, gravity) = StreamFeatures.windowFeatures(
            acc2, gyr2, if (full) acc4 else null, if (full) gyr4 else null,
            prevGravity, tMs, tremorWindowS,
        )
        prevGravity = gravity
        return feats
    }

    fun reset() {
        for (a in accRing + gyrRing) a.fill(0.0)
        head = 0
        n = 0
        prevGravity = null
    }
}
