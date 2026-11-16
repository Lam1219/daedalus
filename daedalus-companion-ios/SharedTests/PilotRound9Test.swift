import Foundation
import XCTest

/// Review round 009 (docs/work/HANDOFF-ios.md § Round 009 fixes).
final class PilotRound9Test: XCTestCase {
    private func draft(code: String) -> PilotDraft {
        var d = PilotDraft(PilotSettings())
        d.participant = code; d.seedText = "7"; d.enrolment = "2026-11-16"
        return d
    }

    /// 3: CHECK is the reserved device-check code that the analysis skips, so a participant can't
    /// be saved under it, in any letter case.
    func test3ReservedCheckCodeIsRefusedInAnyCase() {
        for code in ["CHECK", "check", "Check", "cHeCk", " CHECK "] {
            XCTAssertNil(draft(code: code).applied(to: PilotSettings()), code)
        }
    }

    func test3OtherCodesStillSave() {
        for code in ["P03", "CHECK1", "PCHECK"] {
            XCTAssertNotNil(draft(code: code).applied(to: PilotSettings()), code)
        }
    }
}
