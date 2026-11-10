import Foundation

/// Freezing-of-gait detector from the wrist for automatic cues (model "fog-1.0",
/// Documentation/automatic-cues-design.md, 2.5). It consumes one `WindowFeatures` per second
/// during a walking guard and reports freeze candidates; it does not cue (in v1 the watch starts
/// the walking cue on a candidate).
///
/// Port of daedalus_watch_kit/fog.py, the reference; FogDetector.kt is its Galaxy Watch twin, and
/// the golden vectors in daedalus_watch_kit/golden check all three (SharedTests). Change all
/// copies together and bump `version`.
///
/// - Walking state: `.walking` or `.turning` windows for at least `walkMinS` seconds in a row.
///   The walk's median cadence and arm swing are kept.
/// - Freeze candidate, within `candidateWindowS` windows after walking stops, when both hold:
///   1. trembling in place (`freezeIndex >= freezeIndexThreshold`), or shuffling: cadence lost
///      (`stepRegularity < lostRegularity`) with arm swing below `armSwingShare` of the walk's
///      median while the wrist is not still;
///   2. no posture change (`gravityChangeDeg < postureChangeDeg`), so the person did not sit.
///   If the walk turned in its last `turnLookbackS` seconds, 1–2 must hold for 2 windows in a row.
/// - Voluntary stop: the wrist still (`.still`) for `stillCancelS` windows ends the walk.
/// - After a candidate: `cooldownS` seconds, and a new walking state, before another one.
///
/// Thresholds are provisional, to be tuned on labelled freezes.
struct FogDetector {
    static let version = "fog-1.0"

    static let walkMinS = 4
    /// Windows after the last walking one (the 4 s gait window lags a stop).
    static let candidateWindowS = 3
    static let lostRegularity = 0.3
    /// Set from false alarms on Parkinson@Home (no freeze labels yet, 2026-11-10).
    static let freezeIndexDefault = 4.0
    static let armSwingShare = 0.2
    static let postureChangeDeg = 15.0
    static let turnLookbackS = 3
    static let stillCancelS = 3
    static let cooldownS = 20

    /// The gait window's length, for a walk's start time.
    static let gaitWindowMs: Int64 = 4_000

    struct Params: Equatable {
        var freezeIndexThreshold = FogDetector.freezeIndexDefault
        /// 0 turns the shuffling route off.
        var armSwingShare = FogDetector.armSwingShare
        /// The person's walking-cue rate, if known (not used by the rules yet).
        var cadenceSpm: Double? = nil
    }

    enum Reason: String {
        case freezeIndex = "freeze_index"
        case armSwing = "arm_swing"
    }

    struct Candidate: Equatable {
        var detectedMs: Int64
        var walkStartMs: Int64
        /// End of the last walking window.
        var walkEndMs: Int64
        /// Median cadence of the walk, to one decimal.
        var walkCadenceSpm: Double
        var reason: Reason
        var freezeIndex: Double
        /// This window's arm swing over the walk's median, to three decimals.
        var armSwingShare: Double
        var turning: Bool
        var confidence: Double
        var version = FogDetector.version
    }

    var params: Params

    // The current or just-ended walk: only what the candidate needs.
    private var walkCount = 0
    private var walkStartMs: Int64 = 0
    private var walkArmSwing: [Double] = []
    private var walkCadence: [Double] = []
    /// End of the last walking window, once the walk stopped.
    private var walkEndMs: Int64?
    private var sinceStop = 0
    /// Consecutive windows meeting conditions 1–2.
    private var pending = 0
    private var still = 0
    private var cooldownUntil: Int64 = -1
    /// Whether each of the last `turnLookbackS` walking windows was turning.
    private var recentTurning: [Bool] = []
    private var lastWalkingEndMs: Int64 = 0

    init(params: Params = Params()) {
        self.params = params
    }

    private static func walking(_ w: WindowFeatures) -> Bool { w.context == .walking || w.context == .turning }

    mutating func update(_ w: WindowFeatures) -> Candidate? {
        if Self.walking(w) {
            if walkEndMs != nil { clearWalk() }   // walking again after a stop: start a new walk
            if walkCount == 0 { walkStartMs = w.tEndMs }
            walkCount += 1
            walkArmSwing.append(w.armSwingDps)
            walkCadence.append(w.cadenceSpm)
            lastWalkingEndMs = w.tEndMs
            recentTurning.append(w.context == .turning)
            if recentTurning.count > Self.turnLookbackS { recentTurning.removeFirst() }
            pending = 0; still = 0; sinceStop = 0
            return nil
        }

        // Not walking.
        guard walkCount >= Self.walkMinS else {
            clearWalk()
            return nil
        }
        if walkEndMs == nil { walkEndMs = lastWalkingEndMs }
        sinceStop += 1

        if w.context == .still {
            still += 1
            if still >= Self.stillCancelS { reset() }
            return nil
        }
        still = 0

        if sinceStop > Self.candidateWindowS {
            reset()
            return nil
        }
        if w.tEndMs < cooldownUntil { return nil }

        let swing = StreamMath.median(walkArmSwing)
        let share = swing > 0 ? w.armSwingDps / swing : 1.0
        let trembling = w.freezeIndex >= params.freezeIndexThreshold
        let shuffling = w.stepRegularity < Self.lostRegularity && share < params.armSwingShare
        let ok = (trembling || shuffling) && w.gravityChangeDeg < Self.postureChangeDeg
        pending = ok ? pending + 1 : 0
        let turning = recentTurning.contains(true)
        if pending < (turning ? 2 : 1) { return nil }

        let cand = Candidate(
            detectedMs: w.tEndMs,
            walkStartMs: walkStartMs - Self.gaitWindowMs,
            walkEndMs: walkEndMs ?? lastWalkingEndMs,
            walkCadenceSpm: StreamMath.pyRound(StreamMath.median(walkCadence), 1),
            reason: trembling ? .freezeIndex : .armSwing,
            freezeIndex: w.freezeIndex,
            armSwingShare: StreamMath.pyRound(share, 3),
            turning: turning,
            confidence: confidence(w.freezeIndex, share, trembling))
        cooldownUntil = w.tEndMs + Int64(Self.cooldownS) * 1000
        reset()
        return cand
    }

    private func confidence(_ fi: Double, _ share: Double, _ trembling: Bool) -> Double {
        let r = trembling ? fi / max(params.freezeIndexThreshold, 1e-9) : params.armSwingShare / max(share, 1e-3)
        return StreamMath.pyRound(r >= 1 ? min(1.0, 1 - 0.5 / r) : 0.5 * r, 3)
    }

    private mutating func clearWalk() {
        walkCount = 0
        walkArmSwing.removeAll(keepingCapacity: true)
        walkCadence.removeAll(keepingCapacity: true)
        walkEndMs = nil
    }

    private mutating func reset() {
        clearWalk()
        pending = 0; still = 0; sinceStop = 0
        recentTurning.removeAll(keepingCapacity: true)
    }

    /// All freeze candidates of a recording.
    static func detect(_ windows: [WindowFeatures], params: Params = Params()) -> [Candidate] {
        var det = FogDetector(params: params)
        return windows.compactMap { det.update($0) }
    }
}
