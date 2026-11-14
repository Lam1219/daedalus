import Foundation
import XCTest

/// Review round 007 (docs/work/HANDOFF-ios.md § Round 007 fixes).
final class PilotRound7Test: XCTestCase {
    /// 8: a pending file from before round 006 has no `place_drawn`. Recovered after an update, its
    /// press file must write `place_drawn: null`, never a copy of `place`.
    func test8UnknownDrawnPlaceIsWrittenAsNull() throws {
        let old = """
        {"version":"pilot-1.0","participant":"P03","press_index":4,"day_index":9,"place":"wrist",
         "earbuds_connected":true,"phone_carried":false,"pace_spm":100,"press_ms":1794570000000,
         "beat_start_ms":1794570000060,"beat_stop_ms":null,"checkin":null,
         "samples":{"t_ms":[],"acc":[[],[],[]],"gyr":null}}
        """
        let r = try XCTUnwrap(PressRecord(json: Data(old.utf8)))
        XCTAssertNil(r.placeDrawn)
        let d = try XCTUnwrap(JSONSerialization.jsonObject(with: try r.json()) as? [String: Any])
        XCTAssertTrue(d["place_drawn"] is NSNull, "\(String(describing: d["place_drawn"]))")
        XCTAssertEqual(d["place"] as? String, "wrist")
    }

    /// A press whose draw is known still writes it.
    func test8KnownDrawnPlaceIsWritten() throws {
        var r = PressRecord(participant: "P03", pressIndex: 5, dayIndex: 9, place: .wrist, earbudsConnected: true,
                            phoneCarried: false, paceSpm: 100, pressMs: 1_794_570_000_000)
        r.apply(PressBeat(drawn: .earbuds, pressIndex: 5))
        let d = try XCTUnwrap(JSONSerialization.jsonObject(with: try r.json()) as? [String: Any])
        XCTAssertEqual(d["place_drawn"] as? String, "earbuds")
    }
}
