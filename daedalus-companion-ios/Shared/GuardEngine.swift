import Foundation

/// Guard engine for automatic cues (model "guard-1.0", Documentation/automatic-cues-design.md,
/// 2.2 and 2.7): raw watch samples in, shadow-mode event rows out. Apple Watch only for now; the
/// iPhone app compiles it because it shares the Shared folder.
///
/// Port of daedalus_watch_kit/guard.py, the reference; GuardEngine.kt is its Galaxy Watch twin,
/// and the golden vectors in daedalus_watch_kit/golden carry the expected events (SharedTests).
/// Change all copies together and bump `version`.
///
///     raw samples (uneven times) -> StreamResampler (even 50 Hz grid)
///       -> StreamFeatures.Extractor (one window a second)
///       -> TremorStream.Detector (rest and walk guards) and FogDetector (walk guard)
///       -> event rows (epochMs, name, notes)
///
/// In shadow mode nothing cues: the events are only logged, with action=shadow. The session
/// writes the rows to its events file with source "auto".
///
/// Resampling: a grid every 1000 / fs ms from the first sample, linear interpolation between
/// consecutive samples with numpy.interp's formula (a grid time equal to a sample's time takes
/// that sample's values), grid times truncated to whole milliseconds. A sample with the same time
/// as the previous one is skipped. A gap longer than `maxGapMs`, or a time that goes backwards,
/// restarts the grid at that sample and starts a
/// new feature extractor, so windows never span a gap. The detectors keep their state.
///
/// Notes: key=value pairs separated by ';', keys always in this order; numbers like Python's
/// f"{x:.Nf}" (correctly rounded, ties to even on the exact binary value), booleans true/false.
///
///     GUARD_START          type=guard;mode=<walk|rest>;models=<features>,<tremor>,<fog>
///     AUTO_TREMOR_START    type=tremor;action=shadow;model=;start_ms=;hz=(1);strength=;confidence=(2);threshold_dps=(2)
///     AUTO_TREMOR_END      type=tremor;action=shadow;model=;start_ms=;end_ms=;hz=(1);strength=;peak_dps=(2)
///     AUTO_FOG_CANDIDATE   type=fog;action=shadow;model=;walk_start_ms=;walk_end_ms=;reason=;turning=;
///                          freeze_index=(2);arm_swing_share=(3);confidence=(2);freeze_index_threshold=(2)
///     GUARD_END            type=guard;mode=;windows=<count>;gaps=<count>
///
/// (N) = decimals. A tremor episode still open at `finish` ends there.
///
/// One guard session: push raw samples, collect event rows.
struct GuardEngine {
    static let version = "guard-1.0"

    enum Mode: String {
        case walk, rest
    }

    let mode: Mode
    let tremorWindowS: Int
    private(set) var resampler = StreamResampler()
    private var extractor: StreamFeatures.Extractor
    private(set) var tremor: TremorStream.Detector
    private(set) var fog: FogDetector?
    /// Feature windows so far.
    private(set) var windows = 0
    private(set) var lastWindowMs: Int64?
    /// Every row emitted so far, in order.
    private(set) var events: [GuardEvent] = []
    private var sample = [Double](repeating: 0, count: 6)

    init(mode: Mode, tremorParams: TremorStream.Params = .init(), fogParams: FogDetector.Params = .init(),
         tremorWindowS: Int = 2) {
        self.mode = mode
        self.tremorWindowS = tremorWindowS
        extractor = StreamFeatures.Extractor(tremorWindowS: tremorWindowS)
        tremor = TremorStream.Detector(params: tremorParams)
        fog = mode == .walk ? FogDetector(params: fogParams) : nil
    }

    // MARK: - Lifecycle

    @discardableResult
    mutating func begin(tMs: Int64) -> [GuardEvent] {
        let notes = "type=guard;mode=\(mode.rawValue);models=\(StreamFeatures.version),\(TremorStream.version),\(FogDetector.version)"
        return emit([GuardEvent(epochMs: tMs, name: AutoCueEvents.guardStart, notes: notes)])
    }

    /// One raw sample: acceleration in m/s² with gravity, rotation in rad/s, time in ms.
    @discardableResult
    mutating func push(tMs: Double, ax: Double, ay: Double, az: Double,
                       gx: Double, gy: Double, gz: Double) -> [GuardEvent] {
        sample[0] = ax; sample[1] = ay; sample[2] = az
        sample[3] = gx; sample[4] = gy; sample[5] = gz
        if resampler.push(tMs: tMs, sample) {
            extractor = StreamFeatures.Extractor(tremorWindowS: tremorWindowS)
        }
        var rows: [GuardEvent] = []
        for i in 0..<resampler.outCount {
            if let w = extractor.push(tMs: resampler.outTimes[i],
                                      ax: resampler.value(i, 0), ay: resampler.value(i, 1), az: resampler.value(i, 2),
                                      gx: resampler.value(i, 3), gy: resampler.value(i, 4), gz: resampler.value(i, 5)) {
                window(w, into: &rows)
            }
        }
        return rows.isEmpty ? rows : emit(rows)
    }

    @discardableResult
    mutating func finish(tMs: Int64) -> [GuardEvent] {
        var rows: [GuardEvent] = []
        for case .end(let ep) in tremor.finish(tEndMs: lastWindowMs ?? tMs) {
            rows.append(GuardEvent(epochMs: tMs, name: AutoCueEvents.autoTremorEnd, notes: Self.tremorEndNotes(ep)))
        }
        rows.append(GuardEvent(epochMs: tMs, name: AutoCueEvents.guardEnd,
                               notes: "type=guard;mode=\(mode.rawValue);windows=\(windows);gaps=\(resampler.restarts)"))
        return emit(rows)
    }

    // MARK: - Internals

    private mutating func emit(_ rows: [GuardEvent]) -> [GuardEvent] {
        events.append(contentsOf: rows)
        return rows
    }

    private mutating func window(_ w: WindowFeatures, into rows: inout [GuardEvent]) {
        windows += 1
        lastWindowMs = w.tEndMs
        for e in tremor.update(w) {
            switch e {
            case .start(let ep):
                rows.append(GuardEvent(epochMs: w.tEndMs, name: AutoCueEvents.autoTremorStart, notes: Self.tremorStartNotes(ep)))
            case .end(let ep):
                rows.append(GuardEvent(epochMs: w.tEndMs, name: AutoCueEvents.autoTremorEnd, notes: Self.tremorEndNotes(ep)))
            }
        }
        if let c = fog?.update(w), let threshold = fog?.params.freezeIndexThreshold {
            rows.append(GuardEvent(epochMs: w.tEndMs, name: AutoCueEvents.autoFogCandidate,
                                   notes: Self.fogNotes(c, threshold: threshold)))
        }
    }

    private static func tremorStartNotes(_ ep: TremorStream.Episode) -> String {
        "type=tremor;action=shadow;model=\(ep.version);start_ms=\(ep.startMs);hz=\(fmt(ep.hz, 1));"
            + "strength=\(ep.strength.rawValue);confidence=\(fmt(ep.confidence, 2));"
            + "threshold_dps=\(fmt(ep.thresholdDps, 2))"
    }

    private static func tremorEndNotes(_ ep: TremorStream.Episode) -> String {
        "type=tremor;action=shadow;model=\(ep.version);start_ms=\(ep.startMs);end_ms=\(ep.endMs.map(String.init) ?? "");"
            + "hz=\(fmt(ep.hz, 1));strength=\(ep.strength.rawValue);peak_dps=\(fmt(ep.peakDps, 2))"
    }

    private static func fogNotes(_ c: FogDetector.Candidate, threshold: Double) -> String {
        "type=fog;action=shadow;model=\(c.version);walk_start_ms=\(c.walkStartMs);"
            + "walk_end_ms=\(c.walkEndMs);reason=\(c.reason.rawValue);turning=\(c.turning ? "true" : "false");"
            + "freeze_index=\(Self.fmt(c.freezeIndex, 2));arm_swing_share=\(Self.fmt(c.armSwingShare, 3));"
            + "confidence=\(Self.fmt(c.confidence, 2));freeze_index_threshold=\(Self.fmt(threshold, 2))"
    }

    /// Python's f"{x:.Nf}": the exact binary value correctly rounded, ties to even (C printf
    /// on Apple platforms does the same; no locale, so the decimal point is always '.').
    static func fmt(_ x: Double, _ decimals: Int) -> String {
        String(format: "%.\(decimals)f", x)
    }

    // MARK: - Whole recordings

    /// A whole recording through one guard session: begin at the first sample, finish at the last.
    /// `acc` and `gyr` are three axes each, one value per time.
    static func run(mode: Mode, timesMs: [Int64], acc: [[Double]], gyr: [[Double]],
                    tremorParams: TremorStream.Params = .init(), fogParams: FogDetector.Params = .init()) -> [GuardEvent] {
        guard let first = timesMs.first, let last = timesMs.last else { return [] }
        var eng = GuardEngine(mode: mode, tremorParams: tremorParams, fogParams: fogParams)
        eng.begin(tMs: first)
        for i in timesMs.indices {
            eng.push(tMs: Double(timesMs[i]), ax: acc[0][i], ay: acc[1][i], az: acc[2][i],
                     gx: gyr[0][i], gy: gyr[1][i], gz: gyr[2][i])
        }
        eng.finish(tMs: last)
        return eng.events
    }
}

/// One event row of a guard session, for the session's events file (source "auto").
struct GuardEvent: Equatable {
    var epochMs: Int64
    var name: String
    var notes: String
}

/// Uneven samples in, an even grid out (see GuardEngine). The grid samples of the last `push`
/// are in `outTimes` / `outValues` (row-major, `channels` values per row); both buffers are
/// reused from push to push.
struct StreamResampler {
    static let maxGapMs = 250.0

    let channels: Int
    let step: Double
    let maxGapMs: Double
    /// Gaps and non-increasing times so far.
    private(set) var restarts = 0
    /// Grid times of the last push.
    private(set) var outTimes: [Int64] = []
    /// Grid values of the last push, `channels` per grid time.
    private(set) var outValues: [Double] = []
    var outCount: Int { outTimes.count }

    private var prevT: Double?
    private var prevV: [Double]
    private var t0 = 0.0
    private var k = 0   // index of the next grid point

    init(channels: Int = 6, fs: Double = StreamFeatures.fs, maxGapMs: Double = StreamResampler.maxGapMs) {
        self.channels = channels
        self.step = 1000.0 / fs
        self.maxGapMs = maxGapMs
        prevV = [Double](repeating: 0, count: channels)
        outTimes.reserveCapacity(8)
        outValues.reserveCapacity(8 * channels)
    }

    /// The value of channel `c` at grid row `row` of the last push.
    func value(_ row: Int, _ c: Int) -> Double { outValues[row * channels + c] }

    /// Pushes one sample; the grid samples it completes are in `outTimes` / `outValues`.
    /// Returns true when this sample began a new grid after a gap or a backward time.
    @discardableResult
    mutating func push(tMs t: Double, _ v: [Double]) -> Bool {
        precondition(v.count == channels, "expected \(channels) values")
        outTimes.removeAll(keepingCapacity: true)
        outValues.removeAll(keepingCapacity: true)
        // Same time as the previous sample (batched sensor delivery): skipped, no grid points.
        if let p = prevT, t == p { return false }
        var restarted = false
        if let p = prevT, t < p || t - p > maxGapMs {
            restarts += 1
            prevT = nil
            restarted = true
        }
        guard let tp = prevT else {
            t0 = t
            prevT = t
            for c in 0..<channels { prevV[c] = v[c] }
            outTimes.append(Int64(t))
            outValues.append(contentsOf: v)
            k = 1
            return restarted
        }
        while true {
            let g = t0 + Double(k) * step
            if g > t { break }
            if g == t {
                outValues.append(contentsOf: v)
            } else {
                for c in 0..<channels {
                    let vp = prevV[c]
                    let slope = (v[c] - vp) / (t - tp)
                    outValues.append(slope * (g - tp) + vp)
                }
            }
            outTimes.append(Int64(g))
            k += 1
        }
        prevT = t
        for c in 0..<channels { prevV[c] = v[c] }
        return restarted
    }
}
