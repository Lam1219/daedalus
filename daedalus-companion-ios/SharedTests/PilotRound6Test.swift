import Foundation
import XCTest

/// Review round 006 (docs/work/HANDOFF-ios.md § Round 006 fixes): the press file says what the
/// rule drew and whether the beat fell back, as Android's does.
final class PilotRound6Test: XCTestCase {
    static let t0: Int64 = 1_794_570_000_000

    private func keys(_ r: PressRecord) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: try r.json()) as? [String: Any])
    }

    func test1RealWristPressHasNoFallback() throws {
        var b = PressBeat(drawn: .wrist, pressIndex: 1)
        b.wristPulse(atMs: Self.t0 + 60)
        var r = PressRecord(participant: "P03", pressIndex: 1, dayIndex: 9, place: .wrist, earbudsConnected: false,
                            phoneCarried: false, paceSpm: 100, pressMs: Self.t0)
        r.apply(b)
        let d = try keys(r)
        XCTAssertEqual(d["place"] as? String, "wrist")
        XCTAssertEqual(d["place_drawn"] as? String, "wrist")
        XCTAssertTrue(d["fallback_reason"] is NSNull)
        XCTAssertTrue(d["fallback_ms"] is NSNull)
    }

    /// Never confirmed: the beat started on the wrist, so `place` is the wrist and
    /// `place_drawn` keeps the earbuds the rule drew.
    func test1FallbackBeforeTheStartIsRecorded() throws {
        var b = PressBeat(drawn: .earbuds, pressIndex: 2)
        XCTAssertTrue(b.phoneFailed(reason: "phone_no_confirm", atMs: Self.t0 + 1_000))
        b.wristPulse(atMs: Self.t0 + 1_010)
        var r = PressRecord(participant: "P03", pressIndex: 2, dayIndex: 9, place: .earbuds, earbudsConnected: true,
                            phoneCarried: false, paceSpm: 100, pressMs: Self.t0)
        r.apply(b)
        let d = try keys(r)
        XCTAssertEqual(d["place"] as? String, "wrist")
        XCTAssertEqual(d["place_drawn"] as? String, "earbuds")
        XCTAssertEqual(d["fallback_reason"] as? String, "phone_no_confirm")
        XCTAssertEqual((d["fallback_ms"] as? NSNumber)?.int64Value, Self.t0 + 1_000)
        XCTAssertEqual((d["beat_start_ms"] as? NSNumber)?.int64Value, Self.t0 + 1_010)
    }

    /// Lost mid-beat: `place` stays where the beat started (earbuds), and the fallback is recorded.
    func test1FallbackMidBeatKeepsTheStartPlace() throws {
        var b = PressBeat(drawn: .earbuds, pressIndex: 3)
        XCTAssertTrue(b.phoneStarted(atMs: Self.t0 + 400, forPress: 3))
        XCTAssertTrue(b.phoneFailed(forPress: 3, reason: "earbuds_lost", atMs: Self.t0 + 9_000))
        var r = PressRecord(participant: "P03", pressIndex: 3, dayIndex: 9, place: .earbuds, earbudsConnected: true,
                            phoneCarried: false, paceSpm: 100, pressMs: Self.t0)
        r.apply(b)
        let back = try XCTUnwrap(PressRecord(json: try r.json()))
        XCTAssertEqual(back.place, .earbuds)
        XCTAssertEqual(back.placeDrawn, .earbuds)
        XCTAssertEqual(back.fallbackReason, "earbuds_lost")
        XCTAssertEqual(back.fallbackMs, Self.t0 + 9_000)
        XCTAssertEqual(back.beatStartMs, Self.t0 + 400)
    }

    /// A message about another press records nothing.
    func test1StaleLossRecordsNoFallback() {
        var b = PressBeat(drawn: .earbuds, pressIndex: 4)
        XCTAssertTrue(b.phoneStarted(atMs: Self.t0, forPress: 4))
        XCTAssertFalse(b.phoneFailed(forPress: 3, reason: "earbuds_lost", atMs: Self.t0 + 5))
        XCTAssertNil(b.fallbackReason)
        XCTAssertNil(b.fallbackMs)
    }
}
