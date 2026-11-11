import Foundation
import XCTest

/// The guard session's CSV rows (GuardRows; the same text as GuardSession.kt on the Galaxy Watch).
final class GuardRowsTest: XCTestCase {

    private func cells(_ row: String) -> [String] {
        XCTAssertTrue(row.hasSuffix("\n"))
        return row.dropLast().split(separator: ",", omittingEmptySubsequences: false).map(String.init)
    }

    func testHeaderAddsTheEventColumns() {
        XCTAssertEqual(GuardRows.header,
                       "timestamp_ms,heart_rate_bpm,ax,ay,az,gx,gy,gz,breathing_rate_brpm,spo2_percent,event,event_notes\n")
        XCTAssertEqual(cells(GuardRows.header).count, GuardRows.columns)
        XCTAssertTrue(GuardRows.header.hasPrefix(String(CueDefaults.csvHeader.dropLast()) + ","))
    }

    func testSampleRows() {
        let g = GuardRows.sampleRow(tMs: 1_700_000_000_123, heartRate: 72, ax: 0.1, ay: -9.8, az: 0.123456,
                                    gx: 0.01, gy: -0.02, gz: 3, guardSession: true)
        XCTAssertEqual(g, "1700000000123,72,0.1000,-9.8000,0.1235,0.0100,-0.0200,3.0000,,,,\n")
        XCTAssertEqual(cells(g).count, 12)
        // Outside a guard the row is the one the watch always wrote (10 columns).
        let plain = GuardRows.sampleRow(tMs: 5, heartRate: nil, ax: 1, ay: 2, az: 3, gx: 4, gy: 5, gz: 6, guardSession: false)
        XCTAssertEqual(plain, "5,0,1.0000,2.0000,3.0000,4.0000,5.0000,6.0000,,\n")
        XCTAssertEqual(cells(plain).count, 10)
    }

    func testEventRowHasTwelveColumnsAndOnlyTimeEventNotes() {
        let row = GuardRows.eventRow(GuardEvent(epochMs: 1234, name: "GUARD_BATTERY", notes: "type=guard;battery_pct=80;charging=false"))
        XCTAssertEqual(row, "1234,,,,,,,,,,GUARD_BATTERY,type=guard;battery_pct=80;charging=false\n")
        let c = cells(row)
        XCTAssertEqual(c.count, 12)
        XCTAssertEqual(c[0], "1234")
        XCTAssertTrue(c[1...9].allSatisfy(\.isEmpty))
        XCTAssertEqual(c[10], "GUARD_BATTERY")
        XCTAssertEqual(c[11], "type=guard;battery_pct=80;charging=false")
    }

    func testBatteryAndStuckNotes() {
        XCTAssertEqual(GuardRows.battery(tMs: 1, level: 0.456, charging: true).notes, "type=guard;battery_pct=46;charging=true")
        XCTAssertEqual(GuardRows.battery(tMs: 1, level: -1, charging: false).notes, "type=guard;battery_pct=-1;charging=false")
        XCTAssertEqual(GuardRows.battery(tMs: 1, level: 1, charging: false).name, AutoCueEvents.guardBattery)
        let start = GuardRows.stuckCueStart(tMs: 9, bpm: 84)
        XCTAssertEqual(start.name, "HAPTIC_GAIT_CUE_START")
        XCTAssertEqual(start.notes, "type=manual;trigger=im_stuck;bpm=84")
        let end = GuardRows.stuckCueEnd(tMs: 10)
        XCTAssertEqual(end.name, "HAPTIC_GAIT_CUE_END")
        XCTAssertEqual(end.notes, "type=manual;trigger=stop")
    }

    func testModes() {
        XCTAssertTrue(GuardRows.isGuard("walk_guard"))
        XCTAssertTrue(GuardRows.isGuard("rest_guard"))
        XCTAssertFalse(GuardRows.isGuard("walking"))
        XCTAssertFalse(GuardRows.isGuard(nil))
        XCTAssertEqual(GuardRows.engineMode("walk_guard"), .walk)
        XCTAssertEqual(GuardRows.engineMode("rest_guard"), .rest)
        XCTAssertTrue(CueDefaults.sessionFileName(mode: GuardRows.walkGuard, start: .now).hasPrefix("session_walk_guard_"))
    }

    /// A short rest guard written the way the watch writes it: GUARD_START first, samples and
    /// events in between, GUARD_END last; every row has 12 cells and no notes hold a comma.
    func testSyntheticGuardRun() {
        for mode in [GuardEngine.Mode.rest, .walk] {
            var csv = GuardRows.header
            let t0: Int64 = 1_700_000_000_000
            var engine = GuardEngine(mode: mode)
            var events: [GuardEvent] = []
            func write(_ rows: [GuardEvent]) { events += rows; for e in rows { csv += GuardRows.eventRow(e) } }
            write(engine.begin(tMs: t0))
            write([GuardRows.battery(tMs: t0, level: 0.9, charging: false)])
            // 20 s at 50 Hz: a resting hand with a 5 Hz tremor, slightly uneven sample times.
            for i in 0..<1000 {
                let t = t0 + Int64(i) * 20 + Int64(i % 3)
                let s = Double(t - t0) / 1000
                let gx = 0.6 * sin(2 * .pi * 5 * s), gy = 0.3 * sin(2 * .pi * 5 * s + 1), gz = 0.01
                csv += GuardRows.sampleRow(tMs: t, heartRate: 70, ax: 0.2, ay: -9.7, az: 0.5,
                                           gx: gx, gy: gy, gz: gz, guardSession: true)
                write(engine.push(tMs: Double(t), ax: 0.2, ay: -9.7, az: 0.5, gx: gx, gy: gy, gz: gz))
            }
            write([GuardRows.battery(tMs: t0 + 20_000, level: 0.89, charging: false)])
            write(engine.finish(tMs: t0 + 20_000))

            XCTAssertEqual(events.first?.name, AutoCueEvents.guardStart, "\(mode)")
            XCTAssertEqual(events.last?.name, AutoCueEvents.guardEnd, "\(mode)")
            XCTAssertTrue(events.first!.notes.hasPrefix("type=guard;mode=\(mode.rawValue);models="))
            for e in events { XCTAssertFalse(e.notes.contains(","), "\(e.name): \(e.notes)") }

            let lines = csv.split(separator: "\n", omittingEmptySubsequences: false).dropLast().dropFirst()
            XCTAssertEqual(lines.count, 1000 + events.count)
            for line in lines {
                XCTAssertEqual(line.split(separator: ",", omittingEmptySubsequences: false).count, 12, String(line))
            }
            let eventNames = lines.compactMap { line -> String? in
                let c = line.split(separator: ",", omittingEmptySubsequences: false)
                return c[10].isEmpty ? nil : String(c[10])
            }
            XCTAssertEqual(eventNames, events.map(\.name))
            XCTAssertTrue(eventNames.contains(AutoCueEvents.autoTremorStart), "\(mode): \(eventNames)")
        }
    }
}
