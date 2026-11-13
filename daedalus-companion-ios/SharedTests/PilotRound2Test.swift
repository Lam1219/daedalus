import Foundation
import XCTest

/// Review round 002 (docs/work/HANDOFF-ios.md § Round 002 fixes): one test per fix a unit test
/// can reach. Numbers are the review's.
final class PilotRound2Test: XCTestCase {
    private func tempDir() throws -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("pilot-r2-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: d) }
        return d
    }

    // MARK: 15: a study press writes no ordinary session CSV

    func test15StudyPressWritesNoSessionCsv() {
        XCTAssertFalse(Pilot.writesSessionCsv(studyPress: true))
        XCTAssertTrue(Pilot.writesSessionCsv(studyPress: false))   // outside a study press: as before
    }

    // MARK: 9: the switch shows when settings aren't complete; turning on resets the draft

    func test9IncompleteMessageWhenOnButNotActive() {
        var s = PilotSettings()
        XCTAssertNil(s.statusMessageKey)
        s.enabled = true                       // e.g. saved by an earlier build with no code
        XCTAssertEqual(s.statusMessageKey, "pilot_incomplete")
        s.participant = "P03"; s.seed = 7; s.enrolment = "2026-11-13"
        XCTAssertNil(s.statusMessageKey)
    }

    func test9DraftResetsToSavedSettingsWhenStudyModeTurnsOn() {
        var saved = PilotSettings()
        saved.participant = "P03"; saved.seed = 7; saved.enrolment = "2026-11-13"
        var draft = PilotDraft(saved)
        draft.seedText = "999"                 // an unsaved edit
        draft = draft.afterStudyModeChange(to: saved)
        XCTAssertEqual(draft, PilotDraft(saved))
    }

    // MARK: 10: no seal retry while a press runs

    func test10NoSealRetryWhileAPressRuns() {
        XCTAssertFalse(Pilot.sealRetryShouldRun(pressRunning: true))
        XCTAssertTrue(Pilot.sealRetryShouldRun(pressRunning: false))
    }

    // MARK: 12: the first wrist pulse is reported, so the pending file is saved with its start

    func test12FirstWristPulseIsReportedOnce() {
        var b = PressBeat(drawn: .earbuds, pressIndex: 3)
        b.pendingWasSaved()                        // pressStarted saved it, with no start yet (round 003)
        XCTAssertFalse(b.wristPulse(atMs: 100))    // silent wrist while waiting: nothing to save
        _ = b.phoneFailed()
        XCTAssertTrue(b.wristPulse(atMs: 1_100))   // the fallback's first pulse: save now
        XCTAssertFalse(b.wristPulse(atMs: 1_700))  // later pulses: no new start
        XCTAssertEqual(b.beatStartMs, 1_100)
    }

    // MARK: 14 (optional): an identical incoming copy is dropped, not kept as _2

    func test14IdenticalIncomingFileIsDropped() throws {
        let dir = try tempDir(), inbox = try tempDir()
        _ = try PilotFiles.writeNew(Data("same".utf8), in: dir, name: "a.json")
        let incoming = inbox.appendingPathComponent("x")
        try Data("same".utf8).write(to: incoming)
        let kept = try PilotFiles.moveNew(incoming, into: dir, name: "a.json")
        XCTAssertEqual(kept.lastPathComponent, "a.json")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), ["a.json"])
        XCTAssertFalse(FileManager.default.fileExists(atPath: incoming.path))
    }
}
