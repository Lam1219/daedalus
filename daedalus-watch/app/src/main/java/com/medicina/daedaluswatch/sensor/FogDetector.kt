package com.medicina.daedaluswatch.sensor

/**
 * Freezing-of-gait detector from the wrist (fog-1.1, Documentation/automatic-cues-design.md 2.5).
 * It reads one [WindowFeatures] per second during a walking guard and reports freeze candidates.
 * In v1 the watch starts the walking cue on a candidate; this class does not cue.
 *
 * Port of daedalus_watch_kit/fog.py, the reference; FogDetector.swift is the Apple Watch twin.
 * Change all copies together, bump [FogDetector.VERSION] and regenerate the golden vectors
 * (GoldenVectorsTest checks this file against them).
 *
 * - Walking state: WALKING or TURNING windows for ≥ [FogDetector.WALK_MIN_S] seconds in a row.
 *   The walk's median cadence and arm swing are kept.
 * - Freeze candidate, within [FogDetector.CANDIDATE_WINDOW_S] windows after walking stops, when
 *   (1) trembling in place: freezeIndex ≥ the person's threshold; or shuffling: stepRegularity
 *   < [FogDetector.LOST_REGULARITY] with arm swing below [FogParams.armSwingShare] of the walk's
 *   median while the wrist is not still; and (2) no posture change (the person did not sit down).
 *   If the walk was turning in its last [FogDetector.TURN_LOOKBACK_S] windows, (1)–(2) must hold
 *   for 2 windows in a row (turns produce brief irregular steps).
 * - Voluntary stop: the wrist STILL for [FogDetector.STILL_CANCEL_S] windows cancels the pending
 *   candidate and ends the walk ("I'm stuck" covers akinetic freezes, which look the same).
 * - After a candidate: [FogDetector.COOLDOWN_S] seconds and a new walking state before another.
 *
 * Thresholds are provisional, to be tuned on labelled FoG data. Pure Kotlin, no Android imports.
 */
class FogDetector(val params: FogParams = FogParams()) {
    companion object {
        const val VERSION = "fog-1.1"

        const val WALK_MIN_S = 4
        /** Windows after the last walking one (the 4 s gait window lags a stop). */
        const val CANDIDATE_WINDOW_S = 3
        const val LOST_REGULARITY = 0.3
        /** Set from false alarms on Parkinson@Home (no freeze labels yet, 2026-11-10). */
        const val FREEZE_INDEX_DEFAULT = 4.0
        const val ARM_SWING_SHARE = 0.2
        const val POSTURE_CHANGE_DEG = 15.0
        const val TURN_LOOKBACK_S = 3
        const val STILL_CANCEL_S = 3
        const val COOLDOWN_S = 20

        /** All freeze candidates of a recording. */
        fun detect(windows: List<WindowFeatures>, params: FogParams = FogParams()): List<FogCandidate> {
            val det = FogDetector(params)
            return windows.mapNotNull { det.update(it) }
        }
    }

    /** The current or just-ended walk. */
    private val walk = ArrayList<WindowFeatures>()
    /** Last walking window once the walk stopped. */
    private var walkEnd: WindowFeatures? = null
    private var sinceStop = 0
    /** Consecutive windows meeting conditions 1–2. */
    private var pending = 0
    private var still = 0
    private var cooldownUntil = -1L
    /** The last TURN_LOOKBACK_S walking windows. */
    private val recent = ArrayDeque<WindowFeatures>(TURN_LOOKBACK_S)

    private fun walking(w: WindowFeatures) = w.context == Context.WALKING || w.context == Context.TURNING

    fun update(w: WindowFeatures): FogCandidate? {
        if (walking(w)) {
            // Walking again within the candidate window (a short stop, or a second where walking
            // detection dropped out): the same walk goes on. A longer stop has already reset it.
            walkEnd = null
            walk.add(w)
            if (recent.size == TURN_LOOKBACK_S) recent.removeFirst()
            recent.addLast(w)
            pending = 0
            still = 0
            sinceStop = 0
            return null
        }

        // Not walking.
        if (walk.size < WALK_MIN_S) {
            walk.clear()
            walkEnd = null
            return null
        }
        val end = walkEnd ?: walk.last().also { walkEnd = it }
        sinceStop++

        if (w.context == Context.STILL) {
            still++
            pending = 0   // a still second breaks a run of freeze seconds
            if (still >= STILL_CANCEL_S) reset()
            return null
        }
        still = 0

        if (sinceStop > CANDIDATE_WINDOW_S) {
            reset()
            return null
        }
        if (w.tEndMs < cooldownUntil) return null

        val swing = StreamFeatures.median(walk.map { it.armSwingDps })
        val share = if (swing > 0) w.armSwingDps / swing else 1.0
        val trembling = w.freezeIndex >= params.freezeIndexThreshold
        val shuffling = w.stepRegularity < LOST_REGULARITY && share < params.armSwingShare
        val ok = (trembling || shuffling) && w.gravityChangeDeg < POSTURE_CHANGE_DEG
        pending = if (ok) pending + 1 else 0
        val turning = recent.any { it.context == Context.TURNING }
        if (pending < (if (turning) 2 else 1)) return null

        val cand = FogCandidate(
            detectedMs = w.tEndMs,
            walkStartMs = walk[0].tEndMs - 4000,
            walkEndMs = end.tEndMs,
            walkCadenceSpm = StreamFeatures.pyRound(StreamFeatures.median(walk.map { it.cadenceSpm }), 1),
            reason = if (trembling) FogCandidate.Reason.FREEZE_INDEX else FogCandidate.Reason.ARM_SWING,
            freezeIndex = w.freezeIndex,
            armSwingShare = StreamFeatures.pyRound(share, 3),
            turning = turning,
            confidence = confidence(w.freezeIndex, share, trembling),
        )
        cooldownUntil = w.tEndMs + COOLDOWN_S * 1000L
        reset()
        return cand
    }

    private fun confidence(fi: Double, share: Double, trembling: Boolean): Double {
        val r = if (trembling) fi / maxOf(params.freezeIndexThreshold, 1e-9)
        else params.armSwingShare / maxOf(share, 1e-3)
        return StreamFeatures.pyRound(if (r >= 1) minOf(1.0, 1 - 0.5 / r) else 0.5 * r, 3)
    }

    private fun reset() {
        walk.clear()
        walkEnd = null
        pending = 0
        still = 0
        sinceStop = 0
        recent.clear()
    }
}

data class FogParams(
    val freezeIndexThreshold: Double = FogDetector.FREEZE_INDEX_DEFAULT,
    /** 0 turns the shuffling route off. */
    val armSwingShare: Double = FogDetector.ARM_SWING_SHARE,
    /** The person's walking-cue rate, if known (not used by fog-1.1's rules). */
    val cadenceSpm: Double? = null,
)

data class FogCandidate(
    val detectedMs: Long,
    val walkStartMs: Long,
    /** End of the last walking window. */
    val walkEndMs: Long,
    val walkCadenceSpm: Double,
    val reason: Reason,
    val freezeIndex: Double,
    val armSwingShare: Double,
    val turning: Boolean,
    val confidence: Double,
    val version: String = FogDetector.VERSION,
) {
    enum class Reason(val value: String) { FREEZE_INDEX("freeze_index"), ARM_SWING("arm_swing") }
}
