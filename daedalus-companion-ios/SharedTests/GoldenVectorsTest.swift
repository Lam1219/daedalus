import Foundation
import XCTest

/// The Swift ports of the automatic-cues models (StreamFeatures, TremorStream, FogDetector) must
/// match the Python reference on the golden vectors in daedalus_watch_kit/golden (written by
/// golden/make_golden.py; the Kotlin ports read the same files).
final class GoldenVectorsTest: XCTestCase {
    /// daedalus_watch_kit/golden, found from this file: <repo>/daedalus-companion-ios/SharedTests/.
    static let goldenDir: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // SharedTests
        .deletingLastPathComponent()   // daedalus-companion-ios
        .deletingLastPathComponent()   // repository
        .appendingPathComponent("daedalus_watch_kit/golden", isDirectory: true)

    struct Case {
        let name: String
        let json: [String: Any]
        var resample: Bool { json["resample"] as? Bool ?? false }
        var input: [String: Any] { json["input"] as! [String: Any] }
        var expected: [String: Any] { json["expected"] as! [String: Any] }
        var relTol: Double { (json["tolerance"] as! [String: Any])["relative"] as! Double }
        var absTol: Double { (json["tolerance"] as! [String: Any])["absolute"] as! Double }
    }

    static func loadCases() throws -> [Case] {
        let files = try FileManager.default.contentsOfDirectory(at: goldenDir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try files.map { url in
            let obj = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
            return Case(name: url.deletingPathExtension().lastPathComponent, json: obj as! [String: Any])
        }
    }

    // MARK: - JSON values

    /// A number, or "inf" / "-inf" for infinite values.
    static func number(_ v: Any?) -> Double? {
        switch v {
        case let n as NSNumber: return n.doubleValue
        case let s as String where s == "inf": return .infinity
        case let s as String where s == "-inf": return -.infinity
        default: return nil
        }
    }

    static func doubles(_ v: Any?) -> [Double] { (v as! [Any]).map { number($0)! } }
    static func ints(_ v: Any?) -> [Int64] { (v as! [NSNumber]).map(\.int64Value) }

    static func tremorParams(_ j: [String: Any]) -> TremorStream.Params {
        TremorStream.Params(thresholdDps: number(j["threshold_dps"])!, personHz: number(j["person_hz"]),
                            minProminence: number(j["min_prominence"])!, maxSlowRatio: number(j["max_slow_ratio"])!,
                            maxSlow4Dps: number(j["max_slow4_dps"])!)
    }

    static func fogParams(_ j: [String: Any]) -> FogDetector.Params {
        FogDetector.Params(freezeIndexThreshold: number(j["freeze_index_threshold"])!,
                           armSwingShare: number(j["arm_swing_share"])!, cadenceSpm: number(j["cadence_spm"]))
    }

    /// Evenly spaced samples of a case: (times, acc axes, gyr axes), resampled if flagged.
    static func samples(_ c: Case) -> (t: [Int64], acc: [[Double]], gyr: [[Double]]) {
        let i = c.input
        let t = ints(i["t_ms"])
        let ch = ["ax", "ay", "az", "gx", "gy", "gz"].map { doubles(i[$0]) }
        if c.resample {
            let r = StreamFeatures.resampleUniform(timesMs: t, channels: ch)
            return (r.timesMs, Array(r.channels[0..<3]), Array(r.channels[3..<6]))
        }
        return (t, Array(ch[0..<3]), Array(ch[3..<6]))
    }

    static let featureKeys: [(String, KeyPath<WindowFeatures, Double>)] = [
        ("tremor_dps", \.tremorDps), ("peak_hz", \.peakHz), ("prominence", \.prominence),
        ("slow_dps", \.slowDps), ("motion_dps", \.motionDps), ("mean_rotation_dps", \.meanRotationDps),
        ("acc_std", \.accStd), ("gravity_change_deg", \.gravityChangeDeg), ("freeze_index", \.freezeIndex),
        ("cadence_spm", \.cadenceSpm), ("step_regularity", \.stepRegularity), ("arm_swing_dps", \.armSwingDps),
    ]

    // MARK: - Comparison

    /// Largest differences seen, per feature, over all cases (printed at the end).
    static var worst: [String: (abs: Double, rel: Double, where: String)] = [:]

    /// math.isclose with the file's tolerances.
    func assertClose(_ a: Double, _ e: Double, _ c: Case, _ path: String, file: StaticString = #filePath, line: UInt = #line) {
        let d = abs(a - e)
        let rel = e == 0 ? (d == 0 ? 0 : Double.infinity) : d / abs(e)
        let key = path.components(separatedBy: ".").last ?? path
        if d > (Self.worst[key]?.abs ?? -1) { Self.worst[key] = (d, rel, "\(c.name) \(path)") }
        let ok = d <= max(c.relTol * max(abs(a), abs(e)), c.absTol)
        XCTAssertTrue(ok, "\(c.name) \(path): got \(a), expected \(e) (diff \(d))", file: file, line: line)
    }

    // MARK: - Tests

    func testGoldenDirectoryHasTheCases() throws {
        let names = try Self.loadCases().map(\.name)
        // At least the cases that existed when this test was written; make_golden.py may add more.
        XCTAssertGreaterThanOrEqual(names.count, 11, "golden cases in \(Self.goldenDir.path): \(names)")
        XCTAssertTrue(names.contains("gap_and_duplicate"))
    }

    func testVersionsMatchTheGoldenFiles() throws {
        for c in try Self.loadCases() {
            let v = c.json["versions"] as! [String: String]
            XCTAssertEqual(v["features"], StreamFeatures.version, c.name)
            XCTAssertEqual(v["tremor"], TremorStream.version, c.name)
            XCTAssertEqual(v["fog"], FogDetector.version, c.name)
        }
    }

    func testGoldenVectors() throws {
        let cases = try Self.loadCases()
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let s = Self.samples(c)
            let windows = StreamFeatures.computeFeatures(timesMs: s.t, acc: s.acc, gyr: s.gyr)
            let expWindows = c.expected["windows"] as! [[String: Any]]
            XCTAssertEqual(windows.count, expWindows.count, "\(c.name): window count")
            for (i, (w, e)) in zip(windows, expWindows).enumerated() {
                XCTAssertEqual(w.tEndMs, (e["t_end_ms"] as! NSNumber).int64Value, "\(c.name) windows[\(i)].t_end_ms")
                XCTAssertEqual(w.context.rawValue, e["context"] as? String, "\(c.name) windows[\(i)].context")
                for (key, kp) in Self.featureKeys {
                    assertClose(w[keyPath: kp], Self.number(e[key])!, c, "windows[\(i)].\(key)")
                }
            }

            let episodes = TremorStream.detect(windows, params: Self.tremorParams(c.json["tremor_params"] as! [String: Any]))
            let expEpisodes = c.expected["tremor_episodes"] as! [[String: Any]]
            XCTAssertEqual(episodes.count, expEpisodes.count, "\(c.name): tremor episode count")
            for (i, (ep, e)) in zip(episodes, expEpisodes).enumerated() {
                XCTAssertEqual(ep.startMs, (e["start_ms"] as! NSNumber).int64Value, "\(c.name) episodes[\(i)].start_ms")
                XCTAssertEqual(ep.detectedMs, (e["detected_ms"] as! NSNumber).int64Value, "\(c.name) episodes[\(i)].detected_ms")
                XCTAssertEqual(ep.endMs, (e["end_ms"] as? NSNumber)?.int64Value, "\(c.name) episodes[\(i)].end_ms")
                assertClose(ep.hz, Self.number(e["hz"])!, c, "episodes[\(i)].hz")
                XCTAssertEqual(ep.strength.rawValue, e["strength"] as? String, "\(c.name) episodes[\(i)].strength")
            }

            let candidates = FogDetector.detect(windows, params: Self.fogParams(c.json["fog_params"] as! [String: Any]))
            let expCandidates = c.expected["fog_candidates"] as! [[String: Any]]
            XCTAssertEqual(candidates.count, expCandidates.count, "\(c.name): fog candidate count")
            for (i, (cand, e)) in zip(candidates, expCandidates).enumerated() {
                XCTAssertEqual(cand.detectedMs, (e["detected_ms"] as! NSNumber).int64Value, "\(c.name) fog[\(i)].detected_ms")
                XCTAssertEqual(cand.reason.rawValue, e["reason"] as? String, "\(c.name) fog[\(i)].reason")
                XCTAssertEqual(cand.turning, e["turning"] as? Bool, "\(c.name) fog[\(i)].turning")
            }
        }
        let report = Self.worst.sorted { $0.key < $1.key }
            .map { "  \($0.key): max abs diff \($0.value.abs) (rel \($0.value.rel)) at \($0.value.where)" }
            .joined(separator: "\n")
        print("Largest differences from the Python reference:\n\(report)")
    }

    /// The streaming extractor (ring buffer, one sample at a time) gives exactly the batch windows.
    func testStreamingMatchesBatch() throws {
        for name in ["walk_then_tremble", "jittered_timestamps"] {
            let c = try XCTUnwrap(Self.loadCases().first { $0.name == name })
            let s = Self.samples(c)
            for tremorWindowS in [2, 4] {
                let batch = StreamFeatures.computeFeatures(timesMs: s.t, acc: s.acc, gyr: s.gyr, tremorWindowS: tremorWindowS)
                var ex = StreamFeatures.Extractor(tremorWindowS: tremorWindowS)
                var stream: [WindowFeatures] = []
                for k in 0..<s.t.count {
                    if let w = ex.push(tMs: s.t[k], ax: s.acc[0][k], ay: s.acc[1][k], az: s.acc[2][k],
                                       gx: s.gyr[0][k], gy: s.gyr[1][k], gz: s.gyr[2][k]) { stream.append(w) }
                }
                XCTAssertEqual(stream.count, batch.count, "\(name) \(tremorWindowS) s")
                XCTAssertEqual(stream, batch, "\(name) \(tremorWindowS) s: streaming and batch windows differ")
            }
        }
    }

    /// The 4 s tremor window changes only tremor_dps, peak_hz and prominence, and only from 4 s on.
    func testFourSecondTremorWindow() throws {
        let c = try XCTUnwrap(Self.loadCases().first { $0.name == "rest_tremor_episode" })
        let s = Self.samples(c)
        let w2 = StreamFeatures.computeFeatures(timesMs: s.t, acc: s.acc, gyr: s.gyr, tremorWindowS: 2)
        let w4 = StreamFeatures.computeFeatures(timesMs: s.t, acc: s.acc, gyr: s.gyr, tremorWindowS: 4)
        XCTAssertEqual(w2.count, w4.count)
        XCTAssertEqual(w2[0], w4[0], "before 4 s of data the 2 s window is used")
        for (a, b) in zip(w2, w4) {
            XCTAssertEqual(a.slowDps, b.slowDps)
            XCTAssertEqual(a.accStd, b.accStd)
            XCTAssertEqual(a.armSwingDps, b.armSwingDps)
        }
        // Mid-tremor (5 Hz at 8 deg/s): both windows see it.
        let mid = w4[10]
        XCTAssertEqual(mid.peakHz, 5.0, accuracy: 0.1)
        XCTAssertEqual(mid.tremorDps, 8.0, accuracy: 1.0)
    }

    // MARK: - Guard engine (guard-1.0)

    func testGuardVersionMatchesTheGoldenFiles() throws {
        for c in try Self.loadCases() {
            let v = c.json["versions"] as! [String: String]
            XCTAssertEqual(v["guard"], GuardEngine.version, c.name)
        }
    }

    /// The raw input (not resampled first) through a guard session gives exactly the Python rows.
    func testGuardEventsMatchTheGoldenFiles() throws {
        for c in try Self.loadCases() {
            let i = c.input
            let t = Self.ints(i["t_ms"])
            let ch = ["ax", "ay", "az", "gx", "gy", "gz"].map { Self.doubles(i[$0]) }
            let tp = Self.tremorParams(c.json["tremor_params"] as! [String: Any])
            let fp = Self.fogParams(c.json["fog_params"] as! [String: Any])
            let expected = c.expected["guard_events"] as! [String: Any]
            for mode in [GuardEngine.Mode.walk, .rest] {
                let got = GuardEngine.run(mode: mode, timesMs: t, acc: Array(ch[0..<3]), gyr: Array(ch[3..<6]),
                                          tremorParams: tp, fogParams: fp)
                let exp = (expected[mode.rawValue] as! [[Any]]).map {
                    GuardEvent(epochMs: ($0[0] as! NSNumber).int64Value, name: $0[1] as! String, notes: $0[2] as! String)
                }
                XCTAssertEqual(got.count, exp.count, "\(c.name) \(mode.rawValue): event count")
                for (k, (g, e)) in zip(got, exp).enumerated() {
                    XCTAssertEqual(g, e, "\(c.name) \(mode.rawValue) events[\(k)]")
                }
            }
        }
    }

    /// Python's f"{x:.Nf}" on the exact binary value: 0.125 and 0.375 are exact ties (to even),
    /// 2.675 is 2.67499... in binary.
    func testGuardNumberFormatting() {
        XCTAssertEqual(GuardEngine.fmt(0.125, 2), "0.12")
        XCTAssertEqual(GuardEngine.fmt(0.375, 2), "0.38")
        XCTAssertEqual(GuardEngine.fmt(2.675, 2), "2.67")
        XCTAssertEqual(GuardEngine.fmt(0.25, 1), "0.2")
        XCTAssertEqual(GuardEngine.fmt(6, 2), "6.00")
        XCTAssertEqual(GuardEngine.fmt(0.0625, 3), "0.062")
        XCTAssertEqual(GuardEngine.fmt(1234.5, 0), "1234")
    }

    private static func resampleAll(_ t: [Int64], _ ch: [[Double]]) -> (t: [Int64], ch: [[Double]], restarts: Int) {
        var r = StreamResampler(channels: ch.count)
        var outT: [Int64] = []
        var outCh = ch.map { _ in [Double]() }
        for k in t.indices {
            r.push(tMs: Double(t[k]), ch.map { $0[k] })
            for row in 0..<r.outCount {
                outT.append(r.outTimes[row])
                for c in ch.indices { outCh[c].append(r.value(row, c)) }
            }
        }
        return (outT, outCh, r.restarts)
    }

    func testResamplerPassesEvenInputThrough() {
        let t = (0..<200).map { Int64(1_000 + $0 * 20) }
        let ch = [t.map { sin(Double($0) / 70) }, t.map { Double($0) * 0.5 }]
        let r = Self.resampleAll(t, ch)
        XCTAssertEqual(r.t, t)
        XCTAssertEqual(r.ch, ch)
        XCTAssertEqual(r.restarts, 0)
    }

    func testResamplerMatchesResampleUniformOnJitteredInput() throws {
        let c = try XCTUnwrap(Self.loadCases().first { $0.name == "jittered_timestamps" })
        let t = Self.ints(c.input["t_ms"])
        let ch = ["ax", "ay", "az", "gx", "gy", "gz"].map { Self.doubles(c.input[$0]) }
        let batch = StreamFeatures.resampleUniform(timesMs: t, channels: ch)
        let r = Self.resampleAll(t, ch)
        XCTAssertEqual(r.t, batch.timesMs)
        XCTAssertEqual(r.ch, batch.channels)
        XCTAssertEqual(r.restarts, 0)
    }

    func testResamplerRestartsOnAGapOrABackwardTime() {
        var r = StreamResampler(channels: 1)
        r.push(tMs: 0, [0])
        r.push(tMs: 40, [4])
        XCTAssertEqual(r.outTimes, [20, 40])
        XCTAssertEqual(r.outValues, [2, 4])
        XCTAssertFalse(r.push(tMs: 290, [9]))   // 250 ms: not yet a gap
        XCTAssertEqual(r.outTimes.first, 60)
        XCTAssertTrue(r.push(tMs: 541, [7]))    // 251 ms: a gap, the grid starts again here
        XCTAssertEqual(r.outTimes, [541])
        XCTAssertEqual(r.outValues, [7])
        r.push(tMs: 571, [10])
        XCTAssertEqual(r.outTimes, [561])
        XCTAssertEqual(r.outValues[0], 9, accuracy: 1e-12)
        XCTAssertFalse(r.push(tMs: 571, [3]))   // same time: skipped
        XCTAssertEqual(r.outTimes, [])
        XCTAssertEqual(r.restarts, 1)
        XCTAssertTrue(r.push(tMs: 570, [3]))    // backwards: restart
        XCTAssertEqual(r.outTimes, [570])
        XCTAssertEqual(r.restarts, 2)

        // A gap starts a new feature extractor: windows need 2 s of samples after it.
        var eng = GuardEngine(mode: .rest)
        for k in 0..<150 { eng.push(tMs: Double(k * 20), ax: 0, ay: 0, az: 9.81, gx: 0, gy: 0, gz: 0) }
        XCTAssertEqual(eng.windows, 2)
        for k in 0..<99 { eng.push(tMs: Double(10_000 + k * 20), ax: 0, ay: 0, az: 9.81, gx: 0, gy: 0, gz: 0) }
        XCTAssertEqual(eng.windows, 2)
        eng.push(tMs: Double(10_000 + 99 * 20), ax: 0, ay: 0, az: 9.81, gx: 0, gy: 0, gz: 0)
        XCTAssertEqual(eng.windows, 3)
        XCTAssertEqual(eng.finish(tMs: 12_000).last?.notes, "type=guard;mode=rest;windows=3;gaps=1")
    }

    func testPythonRounding() {
        XCTAssertEqual(StreamMath.pyRound(0.25, 1), 0.2)
        XCTAssertEqual(StreamMath.pyRound(0.35, 1), 0.3)   // 0.35 is 0.34999... in binary
        XCTAssertEqual(StreamMath.pyRound(4.75, 1), 4.8)
        XCTAssertEqual(StreamMath.pyRound(2.675, 2), 2.67)
        XCTAssertEqual(StreamMath.pyRound(0.8333333, 3), 0.833)
        XCTAssertEqual(StreamMath.median([3, 1, 2, 4]), 2.5)
        XCTAssertEqual(StreamMath.median([3, 1, 2]), 2)
    }

    func testFeedbackKeepsThresholdNearBase() {
        var p = TremorStream.Params()
        for _ in 0..<10 { p = TremorStream.afterFeedback(p, falsePositive: true) }
        XCTAssertEqual(p.thresholdDps, 9.0, accuracy: 1e-12)   // 1.5 x 6
        for _ in 0..<20 { p = TremorStream.afterFeedback(p, falsePositive: false) }
        XCTAssertEqual(p.thresholdDps, 3.0, accuracy: 1e-12)   // 0.5 x 6
        let slight = TremorStream.afterFeedback(TremorStream.Params(thresholdDps: 1.5), falsePositive: false, baseDps: 1.5)
        XCTAssertEqual(slight.thresholdDps, TremorStream.tMin)
    }
}

/// A few cases for the 20 s tremor check (tremor-1.0) on synthetic recordings.
final class TremorAnalyzerTest: XCTestCase {
    private func recording(seconds: Double, gyr: (Double) -> (Double, Double, Double)) -> TremorAnalyzer.Result {
        let n = Int(seconds * 50)
        var t: [Int64] = [], x: [Double] = [], y: [Double] = [], z: [Double] = []
        var seed: UInt64 = 42
        func noise() -> Double {   // small deterministic noise, about ±0.002 rad/s
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return (Double(seed >> 11) / Double(1 << 53) - 0.5) * 0.004
        }
        for k in 0..<n {
            let s = Double(k) / 50
            let g = gyr(s)
            t.append(Int64(k * 20)); x.append(g.0 + noise()); y.append(g.1 + noise()); z.append(g.2 + noise())
        }
        return TremorAnalyzer.analyze(timesMs: t, gx: x, gy: y, gz: z)
    }

    func testRestTremorIsFound() {
        let amp = 8.0 * Double.pi / 180 * 2.0.squareRoot()   // 8 deg/s RMS on one axis
        let r = recording(seconds: 20) { s in (amp * sin(2 * .pi * 5 * s), 0, 0) }
        XCTAssertEqual(r.outcome, .tremor)
        XCTAssertEqual(r.frequencyHz ?? 0, 5.0, accuracy: 0.11)
        XCTAssertEqual(r.strength, .light)
        XCTAssertEqual(r.bandDps, 8.0, accuracy: 0.5)
    }

    func testStillHandHasNoTremor() {
        XCTAssertEqual(recording(seconds: 20) { _ in (0, 0, 0) }.outcome, .none)
    }

    func testSlowMovementIsMoved() {
        let amp = 40.0 * Double.pi / 180 * 2.0.squareRoot()
        XCTAssertEqual(recording(seconds: 20) { s in (amp * sin(2 * .pi * 1 * s), 0, 0) }.outcome, .moved)
    }

    func testShortRecordingIsIncomplete() {
        XCTAssertEqual(recording(seconds: 8) { _ in (0, 0, 0) }.outcome, .incomplete)
    }
}
