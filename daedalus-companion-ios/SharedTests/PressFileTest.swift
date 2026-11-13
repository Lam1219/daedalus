import Foundation
import XCTest

/// Press files (B4, B5) round-trip, and match the format pilot_analysis.py reads.
/// Set TEST_RUNNER_PILOT_PRESS_OUT=<folder> on `xcodebuild test` to also write the sample file
/// there, then check it with Python:
///   cd daedalus_watch_kit && python3 -c "from daedalus_watch_kit.pilot_analysis import *; ..."
final class PressFileTest: XCTestCase {
    static let t0: Int64 = 1_794_570_000_000

    /// A synthetic press like test_pilot_analysis.py's: walking at 100 steps/min until 25 s,
    /// a freeze, a press at 28 s, walking again from 33 s. Accelerometer only (gyr null).
    static func sample() -> PressRecord {
        let fs = 50.0, g = 9.80665
        var s = PressSamples()
        var rng = SystemRandomNumberGenerator()
        for i in 0..<Int(90 * fs) {
            let t = Double(i) / fs
            let walking = t < 25 || t >= 33
            let bump = walking ? 2.0 * (sin(2 * .pi * (100.0 / 60) * t) + 0.5 * sin(2 * .pi * (100.0 / 120) * t)) : 0
            let noise = { Double.random(in: -0.03...0.03, using: &rng) }
            s.tMs.append(t0 + Int64((t * 1000).rounded()))
            s.ax.append(0.3 * bump + noise()); s.ay.append(noise()); s.az.append(g + bump + noise())
        }
        return PressRecord(participant: "P03", pressIndex: 12, dayIndex: 9, place: .earbuds, earbudsConnected: true,
                           phoneCarried: false, paceSpm: 100, pressMs: t0 + 28_000, beatStartMs: t0 + 28_400,
                           beatStopMs: t0 + 36_000, checkin: .alreadyMoving, samples: s)
    }

    func testRoundTrip() throws {
        let r = Self.sample()
        let back = try XCTUnwrap(PressRecord(json: try r.json()))
        XCTAssertEqual(NSDictionary(dictionary: back.metadata), NSDictionary(dictionary: r.metadata))
        XCTAssertEqual(back.samples.tMs, r.samples.tMs)
        for (a, b) in zip([back.samples.ax, back.samples.ay, back.samples.az], [r.samples.ax, r.samples.ay, r.samples.az]) {
            XCTAssertEqual(a.count, b.count)
            XCTAssertTrue(zip(a, b).allSatisfy { abs($0 - $1) < 1e-9 })
        }
        XCTAssertEqual(back.samples.tMs.count, 4500)
        XCTAssertNil(back.samples.gyr)
    }

    func testExactKeysAndNulls() throws {
        var r = Self.sample()
        r.beatStopMs = nil; r.checkin = nil
        let d = try XCTUnwrap(JSONSerialization.jsonObject(with: try r.json()) as? [String: Any])
        XCTAssertEqual(Set(d.keys), ["version", "participant", "press_index", "day_index", "place", "earbuds_connected",
                                     "phone_carried", "pace_spm", "press_ms", "beat_start_ms", "beat_stop_ms", "checkin", "samples"])
        XCTAssertEqual(d["version"] as? String, "pilot-1.0")
        XCTAssertTrue(d["beat_stop_ms"] is NSNull)
        XCTAssertTrue(d["checkin"] is NSNull)
        let s = try XCTUnwrap(d["samples"] as? [String: Any])
        XCTAssertEqual(Set(s.keys), ["t_ms", "acc", "gyr"])
        XCTAssertTrue(s["gyr"] is NSNull)
        XCTAssertEqual((s["acc"] as? [[Double]])?.count, 3)
        XCTAssertEqual(r.fileName, "press_P03_00012.json")
    }

    func testMergeKeepsTheWindowAndPrefersTheRecorder() {
        let press: Int64 = 100_000
        let rec = stride(from: press - 40_000, through: press + 20_000, by: 20).map { PressSamples.Sample(tMs: $0, ax: 1, ay: 0, az: 9.8) }
        let live = stride(from: press - 5_000, through: press + 70_000, by: 20).map { PressSamples.Sample(tMs: $0, ax: 2, ay: 0, az: 9.8) }
        let m = PressSamples.merge(recorded: rec, live: live, pressMs: press)
        XCTAssertEqual(m.tMs.first, press - 30_000)
        XCTAssertEqual(m.tMs.last, press + 60_000)
        XCTAssertEqual(zip(m.tMs, m.tMs.dropFirst()).allSatisfy { $0 < $1 }, true)
        // Recorder samples up to +20 s, live samples only after.
        XCTAssertTrue(zip(m.tMs, m.ax).allSatisfy { t, a in t <= press + 20_000 ? a == 1 : a == 2 })
    }

    /// Writes the sample press file for the Python check (only when asked to).
    func testWriteSampleForPython() throws {
        guard let out = ProcessInfo.processInfo.environment["PILOT_PRESS_OUT"] else {
            throw XCTSkip("Set TEST_RUNNER_PILOT_PRESS_OUT to write the sample press file")
        }
        let dir = URL(fileURLWithPath: out, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let r = Self.sample()
        try r.json().write(to: dir.appendingPathComponent(r.fileName))
    }
}
