package com.medicina.daedaluswatch.sensor

import kotlin.math.abs

/**
 * Streaming tremor detector (tremor-stream-1.0, Documentation/automatic-cues-design.md 2.4). It
 * reads one [WindowFeatures] per second and reports tremor episodes. It does not cue: the watch
 * decides what to do with an episode (in v1, ask first with Fine motor).
 *
 * Port of daedalus_watch_kit/tremor_stream.py, the reference; TremorStream.swift is the Apple
 * Watch twin. Change all copies together, bump [TremorStream.VERSION] and regenerate the golden
 * vectors (GoldenVectorsTest checks this file against them). calibrate() is not ported: v1 does
 * not use it.
 *
 * A window is positive when all hold:
 * 1. context is [Context.REST_TREMOR_POSSIBLE] or [Context.STILL] (a slight tremor stays under
 *    the STILL motion limit, so STILL counts);
 * 2. peakHz is in the accepted band: 3.5–7.5 Hz, or the person's tremor Hz ± 1.5 Hz;
 * 3. prominence ≥ [TremorParams.minProminence];
 * 4. tremorDps ≥ the person's threshold T;
 * 5. the hand is quiet around the tremor (rest gate; infinity turns a limit off):
 *    slowDps ≤ maxSlowRatio × tremorDps and armSwingDps ≤ maxSlow4Dps.
 *
 * Episode start: [TremorStream.START_K] positives in the last [TremorStream.START_N] windows whose
 * peak frequencies stay within ± [TremorStream.FREQ_TOLERANCE_HZ] of their median. Episode end: at
 * most [TremorStream.END_K] positives in the last [TremorStream.END_N] windows.
 *
 * Thresholds are provisional (tuned on Parkinson@Home). Pure Kotlin, no Android imports.
 */
object TremorStream {
    const val VERSION = "tremor-stream-1.0"

    const val BAND_LOW_HZ = 3.5
    const val BAND_HIGH_HZ = 7.5
    const val PERSONAL_BAND_HZ = 1.5
    /** Tuned on Parkinson@Home (T4, 2026-11-10). */
    const val MIN_PROMINENCE = 16.0
    const val FREQ_TOLERANCE_HZ = 1.0
    const val START_K = 3
    const val START_N = 4
    const val END_K = 1
    const val END_N = 6

    /** Rest gate, tuned on Parkinson@Home (2026-11-10). */
    const val MAX_SLOW_RATIO = 0.5
    const val MAX_SLOW4_DPS = 8.0

    /** Personal threshold T, deg/s RMS in 3–8 Hz over a 2 s window. */
    const val T_MIN = 1.5
    const val T_MAX = 40.0
    /** Before any feedback: the population value from T4. */
    const val T_DEFAULT = 6.0
    /** "Not shaking". */
    const val FALSE_STEP = 1.15
    /** "I'm shaking" with no detection. */
    const val MISSED_STEP = 0.90
    /** Feedback keeps T within 0.5–1.5 × its starting value. */
    const val FEEDBACK_FLOOR = 0.5
    const val FEEDBACK_CEILING = 1.5

    /** tremor-1.0 strength levels, deg/s. */
    const val MODERATE_DPS = 10.0
    const val STRONG_DPS = 40.0

    private const val EDGE = 1e-9

    /**
     * "Not shaking" ([falsePositive]) raises T by 15 %; a missed tremor lowers it by 10 %. T stays
     * within FEEDBACK_FLOOR–FEEDBACK_CEILING × [baseDps] (the threshold before any feedback) and
     * T_MIN–T_MAX. Clipping as numpy.clip: max with the floor first, then min with the ceiling.
     */
    fun afterFeedback(params: TremorParams, falsePositive: Boolean, baseDps: Double = T_DEFAULT): TremorParams {
        val step = if (falsePositive) FALSE_STEP else MISSED_STEP
        val lo = maxOf(T_MIN, FEEDBACK_FLOOR * baseDps)
        val hi = maxOf(lo, minOf(T_MAX, FEEDBACK_CEILING * baseDps))   // an extreme base can't invert the bounds
        val t = minOf(maxOf(params.thresholdDps * step, lo), hi)
        return params.copy(thresholdDps = t)
    }

    fun windowPositive(w: WindowFeatures, p: TremorParams): Boolean {
        val (lo, hi) = p.band()
        return (w.context == Context.REST_TREMOR_POSSIBLE || w.context == Context.STILL) &&
            w.peakHz >= lo - EDGE && w.peakHz <= hi + EDGE &&
            w.prominence >= p.minProminence &&
            w.tremorDps >= p.thresholdDps &&
            w.slowDps <= p.maxSlowRatio * w.tremorDps &&
            w.armSwingDps <= p.maxSlow4Dps
    }

    fun strength(dps: Double): TremorAnalyzer.Strength = when {
        dps >= STRONG_DPS -> TremorAnalyzer.Strength.STRONG
        dps >= MODERATE_DPS -> TremorAnalyzer.Strength.MODERATE
        else -> TremorAnalyzer.Strength.LIGHT
    }

    /**
     * All finished episodes of a recording, an open one closed at the last window. [positive]:
     * per-window decisions overriding the rule (one per window).
     */
    fun detect(
        windows: List<WindowFeatures>, params: TremorParams = TremorParams(), positive: List<Boolean>? = null,
    ): List<TremorEpisode> {
        require(positive == null || positive.size >= windows.size) { "one positive flag per window" }
        val det = TremorDetector(params)
        val out = ArrayList<TremorEpisode>()
        var lastT = 0L
        windows.forEachIndexed { i, w ->
            lastT = w.tEndMs
            det.update(w, positive?.get(i)).filter { it.kind == TremorEvent.Kind.END }.mapTo(out) { it.episode }
        }
        det.finish(lastT).mapTo(out) { it.episode }
        return out
    }
}

data class TremorParams(
    val thresholdDps: Double = TremorStream.T_DEFAULT,
    /** The person's tremor frequency, if known: narrows the accepted band to ± 1.5 Hz. */
    val personHz: Double? = null,
    val minProminence: Double = TremorStream.MIN_PROMINENCE,
    val maxSlowRatio: Double = TremorStream.MAX_SLOW_RATIO,
    val maxSlow4Dps: Double = TremorStream.MAX_SLOW4_DPS,
) {
    /** Accepted peak band, Hz. */
    fun band(): Pair<Double, Double> = if (personHz == null) {
        TremorStream.BAND_LOW_HZ to TremorStream.BAND_HIGH_HZ
    } else {
        maxOf(TremorStream.BAND_LOW_HZ, personHz - TremorStream.PERSONAL_BAND_HZ) to
            minOf(TremorStream.BAND_HIGH_HZ, personHz + TremorStream.PERSONAL_BAND_HZ)
    }
}

/** A tremor episode; its summary fields are refreshed as positive windows arrive. */
class TremorEpisode(
    /** End of the first positive window in the starting run, minus the 2 s window. */
    val startMs: Long,
    /** When the episode was confirmed: the watch would prompt here. */
    val detectedMs: Long,
    val thresholdDps: Double,
) {
    var endMs: Long? = null
        internal set
    /** Median peak frequency of the positive windows, one decimal. */
    var hz: Double = 0.0
        internal set
    var peakDps: Double = 0.0
        internal set
    var strength: TremorAnalyzer.Strength = TremorAnalyzer.Strength.LIGHT
        internal set
    /** Margin over the threshold mapped to 0–1: 0.5 at T, 0.83 at 3 × T, towards 1 above. */
    var confidence: Double = 0.0
        internal set
    val version: String = TremorStream.VERSION

    internal val hzValues = ArrayList<Double>()
    internal val dpsValues = ArrayList<Double>()

    override fun toString(): String =
        "TremorEpisode(startMs=$startMs, detectedMs=$detectedMs, endMs=$endMs, hz=$hz, peakDps=$peakDps, " +
            "strength=$strength, confidence=$confidence, thresholdDps=$thresholdDps)"
}

class TremorEvent(val kind: Kind, val episode: TremorEpisode) {
    enum class Kind { START, END }
}

/** Feed [WindowFeatures] in time order with [update]; it returns events as they happen. */
class TremorDetector(val params: TremorParams = TremorParams()) {
    companion object {
        const val WINDOW_MS = 2000L
        private val RECENT = maxOf(TremorStream.START_N, TremorStream.END_N)
    }

    private val recentPos = BooleanArray(RECENT)
    private val recentWin = arrayOfNulls<WindowFeatures>(RECENT)
    /** Entries in the ring, up to RECENT; the newest is at (head - 1). */
    private var count = 0
    private var head = 0

    var episode: TremorEpisode? = null
        private set

    /** The i-th of the last [n] entries, oldest first. */
    private fun index(n: Int, i: Int) = ((head - n + i) % RECENT + RECENT) % RECENT

    /**
     * Returns no events, or START / END events. [positive] overrides the window rule (e.g. with a
     * classifier's decision); the episode logic stays the same.
     */
    fun update(w: WindowFeatures, positive: Boolean? = null): List<TremorEvent> {
        val pos = positive ?: TremorStream.windowPositive(w, params)
        recentPos[head] = pos
        recentWin[head] = w
        head = (head + 1) % RECENT
        if (count < RECENT) count++

        val ep = episode
        if (ep == null) {
            if (!pos) return emptyList()
            val n = minOf(count, TremorStream.START_N)
            val hits = ArrayList<WindowFeatures>()
            for (i in 0 until n) {
                val j = index(n, i)
                if (recentPos[j]) hits.add(recentWin[j]!!)
            }
            if (hits.size < TremorStream.START_K) return emptyList()
            val med = StreamFeatures.median(hits.map { it.peakHz })
            if (hits.any { abs(it.peakHz - med) > TremorStream.FREQ_TOLERANCE_HZ + 1e-9 }) return emptyList()
            val started = TremorEpisode(hits[0].tEndMs - WINDOW_MS, w.tEndMs, params.thresholdDps)
            for (x in hits) {
                started.hzValues.add(x.peakHz)
                started.dpsValues.add(x.tremorDps)
            }
            refresh(started)
            episode = started
            return listOf(TremorEvent(TremorEvent.Kind.START, started))
        }

        if (pos) {
            ep.hzValues.add(w.peakHz)
            ep.dpsValues.add(w.tremorDps)
            refresh(ep)
        }
        if (count < TremorStream.END_N) return emptyList()
        var positives = 0
        for (i in 0 until TremorStream.END_N) if (recentPos[index(TremorStream.END_N, i)]) positives++
        if (positives > TremorStream.END_K) return emptyList()
        // The episode ended after the last positive window still in memory.
        var lastPos: WindowFeatures? = null
        for (i in 0 until count) {
            val j = index(count, i)
            if (recentPos[j]) lastPos = recentWin[j]
        }
        ep.endMs = lastPos?.tEndMs ?: w.tEndMs
        episode = null
        return listOf(TremorEvent(TremorEvent.Kind.END, ep))
    }

    private fun refresh(ep: TremorEpisode) {
        ep.hz = StreamFeatures.pyRound(StreamFeatures.median(ep.hzValues), 1)
        ep.peakDps = ep.dpsValues.maxOf { it }
        val medDps = StreamFeatures.median(ep.dpsValues)
        ep.strength = TremorStream.strength(medDps)
        val ratio = medDps / maxOf(params.thresholdDps, 1e-9)
        ep.confidence = StreamFeatures.pyRound(if (ratio >= 1) 1 - 0.5 / ratio else 0.5 * ratio, 3)
    }

    /** Close an open episode at the end of a recording. */
    fun finish(tEndMs: Long): List<TremorEvent> {
        val ep = episode ?: return emptyList()
        ep.endMs = tEndMs
        episode = null
        return listOf(TremorEvent(TremorEvent.Kind.END, ep))
    }
}
