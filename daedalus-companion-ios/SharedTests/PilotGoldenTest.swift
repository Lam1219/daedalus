import Foundation
import XCTest

/// The Swift port of the home-pilot rules (Shared/Pilot.swift) must match the Python reference
/// (daedalus_watch_kit/daedalus_watch_kit/pilot.py) on golden/pilot/pilot.json, row for row.
/// 64-bit values are hex strings in the file.
final class PilotGoldenTest: XCTestCase {
    static var golden: [String: Any] {
        let url = GoldenVectorsTest.goldenDir.appendingPathComponent("pilot/pilot.json")
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }

    private func hex(_ v: Any?) -> UInt64 { UInt64(v as! String, radix: 16)! }
    private func rows(_ key: String) -> [[String: Any]] { Self.golden[key] as! [[String: Any]] }
    private func int64(_ v: Any?) -> Int64 { (v as! NSNumber).int64Value }

    func testVersionOrderAndConstants() {
        let g = Self.golden
        XCTAssertEqual(g["version"] as? String, Pilot.version)
        XCTAssertEqual(g["places_order"] as? [String], Pilot.places.map(\.rawValue))
        let c = g["constants"] as! [String: Any]
        XCTAssertEqual(c["learning_days"] as? Int, Pilot.learningDays)
        XCTAssertEqual(c["pilot_days"] as? Int, Pilot.pilotDays)
        XCTAssertEqual(int64(c["max_seed"]), Pilot.maxSeed)
        XCTAssertEqual(c["max_checkins_per_day"] as? Int, Pilot.maxCheckinsPerDay)
        XCTAssertEqual(int64(c["reminder_gap_ms"]), Pilot.reminderGapMs)
        XCTAssertEqual(c["phone_carried_window_s"] as? Int, Pilot.phoneCarriedWindowS)
        if let v = c["study_max_cue_s"] { XCTAssertEqual(v as? Int, Pilot.studyMaxCueS) }
        if let v = c["buffer_before_s"] { XCTAssertEqual(v as? Int, Pilot.bufferBeforeS) }
        if let v = c["buffer_after_s"] { XCTAssertEqual(v as? Int, Pilot.bufferAfterS) }
    }

    func testSplitMix64() {
        let r = rows("splitmix64")
        XCTAssertFalse(r.isEmpty)
        for row in r { XCTAssertEqual(Pilot.splitmix64(hex(row["input"])), hex(row["output"]), "\(row)") }
    }

    func testDraws() {
        let r = rows("draws")
        XCTAssertFalse(r.isEmpty)
        for row in r {
            XCTAssertEqual(Pilot.draw(seed: int64(row["seed"]), pressIndex: int64(row["press_index"])), hex(row["draw"]), "\(row)")
        }
    }

    func testChoosePlaceAllRows() {
        let r = rows("choose_place")
        XCTAssertEqual(r.count, 1008)
        var mismatches = 0
        for row in r {
            let got = Pilot.choosePlace(seed: int64(row["seed"]), pressIndex: int64(row["press_index"]),
                                        dayIndex: (row["day_index"] as! NSNumber).intValue,
                                        earbudsConnected: row["earbuds_connected"] as! Bool,
                                        phoneCarried: row["phone_carried"] as! Bool)
            if got.rawValue != row["place"] as? String { mismatches += 1; XCTFail("\(row) -> \(got)") }
        }
        XCTAssertEqual(mismatches, 0)
    }

    func testShouldRemindPocket() {
        let r = rows("should_remind_pocket")
        XCTAssertEqual(r.count, 128)
        for row in r {
            let got = Pilot.shouldRemindPocket(
                nowMs: int64(row["now_ms"]), stoodUp: row["stood_up"] as! Bool,
                phoneStepsLastMin: (row["phone_steps_last_min"] as! NSNumber).intValue,
                phoneMovedLastMin: row["phone_moved_last_min"] as! Bool,
                lastReminderMs: (row["last_reminder_ms"] as? NSNumber)?.int64Value,
                beatPlaying: row["beat_playing"] as! Bool, enabled: row["enabled"] as! Bool)
            XCTAssertEqual(got, row["remind"] as! Bool, "\(row)")
        }
    }

    func testShouldAskCheckin() {
        let r = rows("should_ask_checkin")
        XCTAssertFalse(r.isEmpty)
        for row in r {
            XCTAssertEqual(Pilot.shouldAskCheckin(askedToday: row["asked_today"] as! Int), row["ask"] as! Bool, "\(row)")
        }
    }
}
