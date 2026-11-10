import Foundation

/// Streaming tremor detector for automatic cues (model "tremor-stream-1.0",
/// Documentation/automatic-cues-design.md, 2.4). It consumes one `WindowFeatures` per second and
/// reports tremor episodes; it does not cue (the watch decides what to do with an episode).
///
/// Port of daedalus_watch_kit/tremor_stream.py, the reference; TremorStream.kt is its Galaxy
/// Watch twin, and the golden vectors in daedalus_watch_kit/golden check all three (SharedTests).
/// Change all copies together and bump `version`.
///
/// A window is positive when all hold:
/// 1. context is `.restTremorPossible` or `.still` (a slight tremor stays under the still limit);
/// 2. `peakHz` is in the accepted band: 3.5–7.5 Hz, or the person's tremor Hz ± 1.5 Hz;
/// 3. `prominence >= minProminence`;
/// 4. `tremorDps >= thresholdDps`, the person's threshold T;
/// 5. the hand is quiet around the tremor (rest gate; `.infinity` turns a limit off):
///    `slowDps <= maxSlowRatio × tremorDps` and `armSwingDps <= maxSlow4Dps`.
///
/// Episode start: `startK` positives in the last `startN` windows whose peak frequencies stay
/// within ±`freqToleranceHz` of their median. Episode end: at most `endK` positives in the last
/// `endN` windows. Thresholds are provisional (tuned on Parkinson@Home, 2026-11-10).
enum TremorStream {
    static let version = "tremor-stream-1.0"

    static let bandLowHz = 3.5
    static let bandHighHz = 7.5
    static let personalBandHz = 1.5
    static let minProminence = 16.0
    static let freqToleranceHz = 1.0
    static let startK = 3, startN = 4
    static let endK = 1, endN = 6

    // Rest gate.
    static let maxSlowRatio = 0.5
    static let maxSlow4Dps = 8.0

    /// Personal threshold T, deg/s RMS in 3–8 Hz over a 2 s window.
    static let tMin = 1.5
    static let tMax = 40.0
    /// Before calibration: the population value.
    static let tDefault = 6.0
    /// "Not shaking" raises T by 15 %; "I'm shaking" with no detection lowers it by 10 %.
    static let falseStep = 1.15
    static let missedStep = 0.90
    /// Feedback keeps T within 0.5–1.5 × its value before any feedback.
    static let feedbackFloor = 0.5
    static let feedbackCeiling = 1.5

    // tremor-1.0 strength levels, deg/s.
    static let moderateDps = 10.0
    static let strongDps = 40.0

    /// The window's length, for an episode's start time.
    static let windowMs: Int64 = 2_000

    struct Params: Equatable {
        var thresholdDps = TremorStream.tDefault
        var personHz: Double? = nil
        var minProminence = TremorStream.minProminence
        var maxSlowRatio = TremorStream.maxSlowRatio
        var maxSlow4Dps = TremorStream.maxSlow4Dps

        /// The accepted peak band, Hz.
        var band: (low: Double, high: Double) {
            guard let hz = personHz else { return (bandLowHz, bandHighHz) }
            return (max(bandLowHz, hz - personalBandHz), min(bandHighHz, hz + personalBandHz))
        }
    }

    /// `falsePositive` true for "Not shaking" (T up 15 %), false for a missed tremor (T down
    /// 10 %). T stays within `feedbackFloor`–`feedbackCeiling` × `baseDps`, the threshold before
    /// any feedback, and within `tMin`–`tMax`.
    static func afterFeedback(_ params: Params, falsePositive: Bool, baseDps: Double = tDefault) -> Params {
        let step = falsePositive ? falseStep : missedStep
        let lo = max(tMin, feedbackFloor * baseDps)
        let hi = max(lo, min(tMax, feedbackCeiling * baseDps))   // an extreme base can't invert the bounds
        var p = params
        p.thresholdDps = StreamMath.clip(params.thresholdDps * step, lo, hi)
        return p
    }

    static func windowPositive(_ w: WindowFeatures, _ p: Params) -> Bool {
        let (lo, hi) = p.band
        return (w.context == .restTremorPossible || w.context == .still)
            && lo - 1e-9 <= w.peakHz && w.peakHz <= hi + 1e-9
            && w.prominence >= p.minProminence
            && w.tremorDps >= p.thresholdDps
            && w.slowDps <= p.maxSlowRatio * w.tremorDps
            && w.armSwingDps <= p.maxSlow4Dps
    }

    static func strength(_ dps: Double) -> TremorAnalyzer.Strength {
        dps >= strongDps ? .strong : dps >= moderateDps ? .moderate : .light
    }

    struct Episode: Equatable {
        /// End of the first positive window in the starting run, minus the 2 s window.
        var startMs: Int64
        /// When the episode was confirmed: the watch would prompt here.
        var detectedMs: Int64
        var endMs: Int64? = nil
        /// Median peak frequency of the positive windows, to one decimal.
        var hz = 0.0
        var peakDps = 0.0
        var strength: TremorAnalyzer.Strength = .light
        /// Margin over the threshold mapped to 0–1: 0.5 at T, 0.83 at 3 × T, towards 1 above.
        var confidence = 0.0
        var thresholdDps = 0.0
        var version = TremorStream.version
    }

    enum Event: Equatable {
        case start(Episode)
        case end(Episode)
    }

    /// Feed windows in time order with `update`; it returns events as they happen.
    struct Detector {
        var params: Params
        private(set) var episode: Episode?
        private var recent: [(positive: Bool, window: WindowFeatures)] = []
        private var episodeHz: [Double] = []
        private var episodeDps: [Double] = []

        init(params: Params = Params()) {
            self.params = params
            recent.reserveCapacity(max(startN, endN) + 1)
        }

        /// `positive` overrides the window rule (e.g. a classifier's decision); the episode
        /// logic stays the same.
        mutating func update(_ w: WindowFeatures, positive: Bool? = nil) -> [Event] {
            let pos = positive ?? windowPositive(w, params)
            recent.append((pos, w))
            if recent.count > max(startN, endN) { recent.removeFirst() }
            var events: [Event] = []

            if episode == nil {
                let hits = recent.suffix(startN).filter(\.positive).map(\.window)
                if hits.count >= startK && pos {
                    let med = StreamMath.median(hits.map(\.peakHz))
                    if hits.allSatisfy({ abs($0.peakHz - med) <= freqToleranceHz + 1e-9 }) {
                        var ep = Episode(startMs: hits[0].tEndMs - windowMs, detectedMs: w.tEndMs,
                                         thresholdDps: params.thresholdDps)
                        episodeHz = hits.map(\.peakHz)
                        episodeDps = hits.map(\.tremorDps)
                        refresh(&ep)
                        episode = ep
                        events.append(.start(ep))
                    }
                }
            } else if var ep = episode {
                if pos {
                    episodeHz.append(w.peakHz)
                    episodeDps.append(w.tremorDps)
                    refresh(&ep)
                }
                let last = recent.suffix(endN)
                if last.count == endN && last.filter(\.positive).count <= endK {
                    // The episode ended after the last positive window.
                    ep.endMs = recent.last(where: \.positive)?.window.tEndMs ?? w.tEndMs
                    episode = nil
                    events.append(.end(ep))
                } else {
                    episode = ep
                }
            }
            return events
        }

        private func refresh(_ ep: inout Episode) {
            let medDps = StreamMath.median(episodeDps)
            ep.hz = StreamMath.pyRound(StreamMath.median(episodeHz), 1)
            ep.peakDps = episodeDps.max() ?? 0
            ep.strength = strength(medDps)
            let ratio = medDps / max(params.thresholdDps, 1e-9)
            ep.confidence = StreamMath.pyRound(ratio >= 1 ? 1 - 0.5 / ratio : 0.5 * ratio, 3)
        }

        /// Closes an open episode at the end of a recording.
        mutating func finish(tEndMs: Int64) -> [Event] {
            guard var ep = episode else { return [] }
            ep.endMs = tEndMs
            episode = nil
            return [.end(ep)]
        }
    }

    /// All finished episodes of a recording. `positive`: per-window decisions overriding the rule.
    static func detect(_ windows: [WindowFeatures], params: Params = Params(), positive: [Bool]? = nil) -> [Episode] {
        var det = Detector(params: params)
        var out: [Episode] = []
        var lastT: Int64 = 0
        for (i, w) in windows.enumerated() {
            lastT = w.tEndMs
            for case .end(let ep) in det.update(w, positive: positive?[i]) { out.append(ep) }
        }
        for case .end(let ep) in det.finish(tEndMs: lastT) { out.append(ep) }
        return out
    }
}

// MARK: - numpy / Python helpers

/// The numpy and Python behaviours the stream models rely on, so the ports match the reference
/// exactly. Used by TremorStream and FogDetector.
enum StreamMath {
    /// numpy.median: the middle value, or the mean of the middle two for an even count.
    static func median(_ x: [Double]) -> Double {
        precondition(!x.isEmpty, "median of no values")
        let s = x.sorted()
        let m = s.count / 2
        return s.count % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2
    }

    static func clip(_ x: Double, _ lo: Double, _ hi: Double) -> Double { min(max(x, lo), hi) }

    /// Python's round(x, digits): the decimal nearest to the exact binary value, ties to even
    /// (round(0.25, 1) == 0.2). printf rounds the same way.
    static func pyRound(_ x: Double, _ digits: Int) -> Double {
        guard x.isFinite else { return x }
        return Double(String(format: "%.\(digits)f", x)) ?? x
    }
}
