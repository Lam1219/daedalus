import Foundation
import XCTest

/// Review round 003 (docs/work/HANDOFF-ios.md § Round 003 fixes).
final class PilotRound3Test: XCTestCase {
    /// 9: a direct wrist press plays its first pulse before the pending file is first saved
    /// (`pressStarted`), which already carries the start: that pulse asks for no second save.
    func test9DirectWristPressAsksForNoExtraSave() {
        var b = PressBeat(drawn: .wrist, pressIndex: 1)
        XCTAssertFalse(b.wristPulse(atMs: 100))    // first pulse, before pressStarted's save
        b.pendingWasSaved()                        // pressStarted: the save includes the start
        XCTAssertFalse(b.wristPulse(atMs: 700))
        XCTAssertEqual(b.beatStartMs, 100)
    }

    /// 9: the fallback's first wrist pulse sets the start after the pending file was saved
    /// without one: that pulse asks for a save.
    func test9FallbackFirstPulseAsksForASave() {
        var b = PressBeat(drawn: .earbuds, pressIndex: 2)
        b.pendingWasSaved()                        // pressStarted, no start yet
        XCTAssertTrue(b.phoneFailed())
        XCTAssertTrue(b.wristPulse(atMs: 1_100))
        XCTAssertFalse(b.wristPulse(atMs: 1_700))
    }
}
