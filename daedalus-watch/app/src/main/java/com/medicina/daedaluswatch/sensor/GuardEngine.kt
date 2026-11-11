package com.medicina.daedaluswatch.sensor

import com.medicina.daedaluswatch.model.EventLabels
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Guard engine (guard-1.0, Documentation/automatic-cues-design.md 2.2, 2.7): raw watch samples in,
 * shadow-mode event rows out. This is what a guard session runs on the watch:
 *
 *     raw samples (uneven times) -> [StreamResampler] (even 50 Hz grid)
 *       -> [StreamFeatureExtractor] (one window a second)
 *       -> [TremorDetector] (rest and walk guards) and [FogDetector] (walk guard)
 *       -> event rows: [GuardEvent] (epochMs, name, notes)
 *
 * In shadow mode nothing cues: the events are only logged, with action=shadow, for evaluation and
 * for learning each person's threshold later. The session writes the rows to the session's events
 * file with source "auto".
 *
 * Port of daedalus_watch_kit/guard.py, the reference; GuardEngine.swift is the Apple Watch twin.
 * Change all copies together, bump [GuardEngine.VERSION] and regenerate the golden vectors
 * (GoldenVectorsTest checks this file against expected.guard_events).
 *
 * Resampling: a grid point every 1000 / FS ms from the first sample, linear interpolation between
 * consecutive samples with numpy.interp's formula (a grid time equal to a sample's time takes that
 * sample's values); grid times truncated to whole ms. A sample with the same time as the previous
 * one is skipped. A gap longer than [GuardEngine.MAX_GAP_MS], or a time that goes backwards,
 * restarts the grid at that sample and starts a new feature
 * extractor: windows never span a gap. The detectors keep their state across a gap.
 *
 * Event notes: key=value pairs separated by ';', keys always in this order, numbers formatted like
 * Python's f"{x:.Nf}" ([GuardEngine.fmt]: correctly rounded, ties to even on the exact binary
 * value), booleans "true"/"false". The phones parse them by key.
 *
 *     GUARD_START          type=guard;mode=<walk|rest>;models=<features>,<tremor>,<fog>
 *     AUTO_TREMOR_START    type=tremor;action=shadow;model=;start_ms=;hz=(1);strength=;confidence=(2);threshold_dps=(2)
 *     AUTO_TREMOR_END      type=tremor;action=shadow;model=;start_ms=;end_ms=;hz=(1);strength=;peak_dps=(2)
 *     AUTO_FOG_CANDIDATE   type=fog;action=shadow;model=;walk_start_ms=;walk_end_ms=;reason=;turning=;
 *                          freeze_index=(2);arm_swing_share=(3);confidence=(2);freeze_index_threshold=(2)
 *     GUARD_END            type=guard;mode=;windows=<count>;gaps=<count>
 *
 * (N) = decimals. A tremor episode still open at [GuardEngine.finish] ends there.
 * One guard session: [begin], [push] raw samples, [finish]; each returns the new rows.
 * Pure Kotlin, no Android imports.
 */
class GuardEngine(
    val mode: String,
    tremorParams: TremorParams? = null,
    fogParams: FogParams? = null,
    private val tremorWindowS: Int = 2,
) {
    companion object {
        const val VERSION = "guard-1.0"
        const val MAX_GAP_MS = 250
        const val MODE_WALK = "walk"
        const val MODE_REST = "rest"

        /** Python's f"{x:.Nf}": the exact binary value correctly rounded, ties to even. */
        fun fmt(x: Double, decimals: Int): String {
            if (x.isNaN()) return "nan"
            if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
            val s = BigDecimal(x).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
            // Python keeps the sign of a negative value that rounds to zero (and of -0.0).
            val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
            return if (negative && !s.startsWith("-")) "-$s" else s
        }

        private fun bool(b: Boolean) = if (b) "true" else "false"

        /** A whole recording through one guard session: begin at the first sample, finish at the last. */
        fun run(
            mode: String, tMs: LongArray, acc: Array<DoubleArray>, gyr: Array<DoubleArray>,
            tremorParams: TremorParams? = null, fogParams: FogParams? = null,
        ): List<GuardEvent> {
            val eng = GuardEngine(mode, tremorParams, fogParams)
            eng.begin(tMs[0])
            for (i in tMs.indices) {
                eng.push(tMs[i].toDouble(), acc[0][i], acc[1][i], acc[2][i], gyr[0][i], gyr[1][i], gyr[2][i])
            }
            eng.finish(tMs[tMs.size - 1])
            return eng.events
        }
    }

    init {
        require(mode == MODE_WALK || mode == MODE_REST) { "mode must be 'walk' or 'rest'" }
    }

    val resampler = StreamResampler()
    private var extractor = StreamFeatureExtractor(tremorWindowS)
    val tremor = TremorDetector(tremorParams ?: TremorParams())
    val fog: FogDetector? = if (mode == MODE_WALK) FogDetector(fogParams ?: FogParams()) else null

    var windows = 0
        private set
    var lastWindowMs: Long? = null
        private set
    private val _events = ArrayList<GuardEvent>()
    val events: List<GuardEvent> get() = _events

    private val sample = DoubleArray(6)

    // ------------------------------------------------------------------ lifecycle

    fun begin(tMs: Long): List<GuardEvent> {
        val notes = "type=guard;mode=$mode;models=${StreamFeatures.VERSION},${TremorStream.VERSION},${FogDetector.VERSION}"
        return emit(listOf(GuardEvent(tMs, EventLabels.GUARD_START, notes)))
    }

    fun push(tMs: Double, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): List<GuardEvent> {
        sample[0] = ax; sample[1] = ay; sample[2] = az
        sample[3] = gx; sample[4] = gy; sample[5] = gz
        if (resampler.push(tMs, sample)) extractor = StreamFeatureExtractor(tremorWindowS)
        var rows: ArrayList<GuardEvent>? = null
        val v = resampler.value
        while (resampler.next()) {
            val w = extractor.push(resampler.timeMs, v[0], v[1], v[2], v[3], v[4], v[5]) ?: continue
            val r = window(w)
            if (r.isNotEmpty()) (rows ?: ArrayList<GuardEvent>().also { rows = it }).addAll(r)
        }
        return emit(rows ?: emptyList())
    }

    fun push(tMs: Long, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): List<GuardEvent> =
        push(tMs.toDouble(), ax, ay, az, gx, gy, gz)

    fun finish(tMs: Long): List<GuardEvent> {
        val rows = ArrayList<GuardEvent>()
        val end = lastWindowMs ?: tMs
        for (e in tremor.finish(end)) rows.add(GuardEvent(tMs, EventLabels.AUTO_TREMOR_END, tremorEndNotes(e.episode)))
        rows.add(GuardEvent(tMs, EventLabels.GUARD_END,
            "type=guard;mode=$mode;windows=$windows;gaps=${resampler.restarts}"))
        return emit(rows)
    }

    // ------------------------------------------------------------------ internals

    private fun emit(rows: List<GuardEvent>): List<GuardEvent> {
        _events.addAll(rows)
        return rows
    }

    private fun window(w: WindowFeatures): List<GuardEvent> {
        windows++
        lastWindowMs = w.tEndMs
        var rows: ArrayList<GuardEvent>? = null
        fun add(e: GuardEvent) = (rows ?: ArrayList<GuardEvent>().also { rows = it }).add(e)
        for (e in tremor.update(w)) {
            if (e.kind == TremorEvent.Kind.START) {
                add(GuardEvent(w.tEndMs, EventLabels.AUTO_TREMOR_START, tremorStartNotes(e.episode)))
            } else {
                add(GuardEvent(w.tEndMs, EventLabels.AUTO_TREMOR_END, tremorEndNotes(e.episode)))
            }
        }
        val f = fog
        if (f != null) {
            val c = f.update(w)
            if (c != null) add(GuardEvent(w.tEndMs, EventLabels.AUTO_FOG_CANDIDATE, fogNotes(c, f.params)))
        }
        return rows ?: emptyList()
    }

    private fun tremorStartNotes(ep: TremorEpisode) =
        "type=tremor;action=shadow;model=${ep.version};start_ms=${ep.startMs};hz=${fmt(ep.hz, 1)};" +
            "strength=${ep.strength.name.lowercase()};confidence=${fmt(ep.confidence, 2)};" +
            "threshold_dps=${fmt(ep.thresholdDps, 2)}"

    private fun tremorEndNotes(ep: TremorEpisode) =
        "type=tremor;action=shadow;model=${ep.version};start_ms=${ep.startMs};end_ms=${ep.endMs};" +
            "hz=${fmt(ep.hz, 1)};strength=${ep.strength.name.lowercase()};peak_dps=${fmt(ep.peakDps, 2)}"

    private fun fogNotes(c: FogCandidate, p: FogParams) =
        "type=fog;action=shadow;model=${c.version};walk_start_ms=${c.walkStartMs};" +
            "walk_end_ms=${c.walkEndMs};reason=${c.reason.value};turning=${bool(c.turning)};" +
            "freeze_index=${fmt(c.freezeIndex, 2)};arm_swing_share=${fmt(c.armSwingShare, 3)};" +
            "confidence=${fmt(c.confidence, 2)};" +
            "freeze_index_threshold=${fmt(p.freezeIndexThreshold, 2)}"
}

/**
 * Uneven samples in, an even grid out. Pull API so nothing is allocated per sample:
 * [push] a sample (returns true when it began a new grid), then call [next] until it returns
 * false; after each true, [timeMs] and [value] hold the grid point. Drain before the next push.
 */
class StreamResampler(
    channels: Int = 6,
    fs: Double = StreamFeatures.FS,
    private val maxGapMs: Double = GuardEngine.MAX_GAP_MS.toDouble(),
) {
    private val step = 1000.0 / fs
    private var hasPrev = false
    private var tp = 0.0
    private var vp = DoubleArray(channels)
    private var t = 0.0
    private var v = DoubleArray(channels)
    private var t0 = 0.0
    /** Index of the next grid point. */
    private var k = 0L
    /** The first sample of a grid is waiting to be emitted. */
    private var firstPending = false

    /** Gaps and backward times so far. */
    var restarts = 0
        private set

    /** The current grid point (valid after [next] returned true; reused buffer). */
    var timeMs = 0L
        private set
    val value = DoubleArray(channels)

    fun push(tMs: Double, values: DoubleArray): Boolean {
        // Same time as the previous sample (batched sensor delivery): skipped, no grid points.
        if (hasPrev && tMs == t) return false
        var restarted = false
        if (hasPrev && (tMs < t || tMs - t > maxGapMs)) {
            restarts++
            hasPrev = false
            restarted = true
        }
        if (!hasPrev) {
            t0 = tMs
            k = 0
            t = tMs
            System.arraycopy(values, 0, v, 0, v.size)
            hasPrev = true
            firstPending = true
            return restarted
        }
        // The previous sample becomes the segment start; swap buffers instead of allocating.
        val tmp = vp
        vp = v
        v = tmp
        tp = t
        t = tMs
        System.arraycopy(values, 0, v, 0, v.size)
        firstPending = false
        return restarted
    }

    fun next(): Boolean {
        if (firstPending) {
            firstPending = false
            timeMs = t.toLong()
            System.arraycopy(v, 0, value, 0, value.size)
            k = 1
            return true
        }
        if (!hasPrev) return false
        val g = t0 + k * step
        if (g > t) return false
        if (g == t) {
            System.arraycopy(v, 0, value, 0, value.size)
        } else {
            val dt = t - tp
            val dg = g - tp
            for (c in value.indices) value[c] = (v[c] - vp[c]) / dt * dg + vp[c]
        }
        timeMs = g.toLong()
        k++
        return true
    }

    /** Convenience for tests: the grid points of one sample, copied. */
    fun pushAll(tMs: Double, values: DoubleArray): Pair<Boolean, List<Pair<Long, DoubleArray>>> {
        val restarted = push(tMs, values)
        val out = ArrayList<Pair<Long, DoubleArray>>()
        while (next()) out.add(timeMs to value.copyOf())
        return restarted to out
    }
}

/** One event row: epoch ms, event label ([EventLabels]), notes. */
data class GuardEvent(val epochMs: Long, val name: String, val notes: String)
