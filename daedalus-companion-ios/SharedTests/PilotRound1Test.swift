import Foundation
import XCTest

/// Review round 001 (docs/work/HANDOFF-ios.md § Round 001 fixes): one test per fix a unit test
/// can reach. Numbers are the review's.
final class PilotRound1Test: XCTestCase {
    static let t0: Int64 = 1_794_570_000_000

    private func record(index: Int64 = 12, pressMs: Int64 = t0) -> PressRecord {
        PressRecord(participant: "P03", pressIndex: index, dayIndex: 9, place: .wrist, earbudsConnected: false,
                    phoneCarried: false, paceSpm: 100, pressMs: pressMs, beatStartMs: pressMs + 60)
    }

    private func tempDir() throws -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("pilot-r1-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: d) }
        return d
    }

    // MARK: 15: names carry press_ms; nothing is ever replaced

    func test15FileNameCarriesPressMs() {
        XCTAssertEqual(record().fileName, "press_P03_00012_\(Self.t0).json")
        // Two installs that both reach press 12 write different names.
        XCTAssertNotEqual(record(pressMs: Self.t0).fileName, record(pressMs: Self.t0 + 1).fileName)
    }

    func test15WritingNeverReplacesAnExistingFile() throws {
        let dir = try tempDir()
        let first = try PilotFiles.writeNew(Data("first".utf8), in: dir, name: "press_P03_00012_1.json")
        let second = try PilotFiles.writeNew(Data("second".utf8), in: dir, name: "press_P03_00012_1.json")
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(try String(contentsOf: first, encoding: .utf8), "first")
        XCTAssertEqual(try String(contentsOf: second, encoding: .utf8), "second")
        XCTAssertEqual(second.pathExtension, "json")   // still an ordinary press file
    }

    func test15MovingIntoTheFolderKeepsBoth() throws {
        let dir = try tempDir(), inbox = try tempDir()
        _ = try PilotFiles.writeNew(Data("old".utf8), in: dir, name: "a.json")
        let incoming = inbox.appendingPathComponent("x")
        try Data("new".utf8).write(to: incoming)
        let moved = try PilotFiles.moveNew(incoming, into: dir, name: "a.json")
        XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent("a.json"), encoding: .utf8), "old")
        XCTAssertEqual(try String(contentsOf: moved, encoding: .utf8), "new")
    }

    // MARK: 16: explicit Save, valid values only, locked while on

    func test16InvalidSeedIsNeverApplied() {
        var base = PilotSettings()
        base.participant = "P03"; base.seed = 42; base.enrolment = "2026-11-13"
        for bad in ["-1", "2147483648", "abc", "", " 12"] {
            var draft = PilotDraft(base)
            draft.seedText = bad
            XCTAssertNil(draft.applied(to: base), bad)
        }
        var ok = PilotDraft(base)
        ok.seedText = "2147483647"
        XCTAssertEqual(ok.applied(to: base)?.seed, 2_147_483_647)
    }

    func test16DraftNeedsCodeAndDate() {
        var draft = PilotDraft(PilotSettings())
        draft.seedText = "5"; draft.enrolment = "2026-11-13"
        XCTAssertNil(draft.applied(to: PilotSettings()))   // no code
        draft.participant = "P03"
        XCTAssertNotNil(draft.applied(to: PilotSettings()))
        draft.enrolment = "2026-13-40"
        XCTAssertNil(draft.applied(to: PilotSettings()))
    }

    func test16FieldsLockWhileStudyModeIsOn() {
        var s = PilotSettings()
        XCTAssertFalse(s.fieldsLocked)
        s.enabled = true
        XCTAssertTrue(s.fieldsLocked)
    }

    // MARK: 17: the iPhone beat has the study limit; queued stops only stop their own press

    func test17PhoneBeatLimitIsTheStudyLimit() {
        XCTAssertEqual(Pilot.phoneBeatLimitS, Pilot.studyMaxCueS)
    }

    // MARK: 18: seal only once the recorder reaches +60 s, else a marked partial file

    func test18WaitsForTheRecorderThenFallsBackMarked() {
        let press = Self.t0, end = press + 60_000
        XCTAssertEqual(Pilot.sealDecision(nowMs: press + 95_000, pressMs: press, recordedLastMs: end - 20_000, isCurrentPress: false), .wait)
        XCTAssertEqual(Pilot.sealDecision(nowMs: press + 95_000, pressMs: press, recordedLastMs: end, isCurrentPress: false), .seal(partial: false))
        XCTAssertEqual(Pilot.sealDecision(nowMs: press + 95_000, pressMs: press, recordedLastMs: nil, isCurrentPress: false), .wait)
        XCTAssertEqual(Pilot.sealDecision(nowMs: press + Pilot.sealGiveUpMs, pressMs: press, recordedLastMs: nil, isCurrentPress: false),
                       .seal(partial: true))
        // Not before the 60 s are over, even with an early recorder sample.
        XCTAssertEqual(Pilot.sealDecision(nowMs: press + 30_000, pressMs: press, recordedLastMs: press + 30_000, isCurrentPress: false), .wait)
    }

    func test18PartialMotionIsRecordedInTheFile() throws {
        var r = record()
        r.motionPartial = true
        let d = try XCTUnwrap(JSONSerialization.jsonObject(with: try r.json()) as? [String: Any])
        XCTAssertEqual(d["motion_partial"] as? Bool, true)
        XCTAssertEqual(PressRecord(json: try r.json())?.motionPartial, true)
    }

    // MARK: 19: the running press is never sealed; an orphan (crash mid-beat) is

    func test19OrphanedPressIsSealedButTheRunningOneIsNot() {
        let press = Self.t0
        XCTAssertEqual(Pilot.sealDecision(nowMs: press + 700_000, pressMs: press, recordedLastMs: press + 60_000, isCurrentPress: true), .wait)
        XCTAssertEqual(Pilot.sealDecision(nowMs: press + 700_000, pressMs: press, recordedLastMs: press + 60_000, isCurrentPress: false),
                       .seal(partial: false))
    }

    func test19PendingAtThePressReadsBackWithNoBeatYet() throws {
        var r = record()
        r.beatStartMs = nil
        let back = try XCTUnwrap(PressRecord(json: try r.json()))
        XCTAssertNil(back.beatStartMs); XCTAssertNil(back.beatStopMs); XCTAssertNil(back.checkin)
    }

    // MARK: 20: pilot folders are excluded from backup

    func test20PilotFolderIsExcludedFromBackup() throws {
        let dir = PilotFiles.directory(try tempDir().appendingPathComponent("pilot"))
        XCTAssertEqual(try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    // MARK: 21: messages for another press are ignored

    func test21StaleReplyDoesNotConfirmTheCurrentPress() {
        var b = PressBeat(drawn: .earbuds, pressIndex: 5)
        XCTAssertFalse(b.phoneStarted(atMs: 400, forPress: 4))
        XCTAssertTrue(b.waitingForPhone)
        XCTAssertTrue(b.phoneStarted(atMs: 450, forPress: 5))
    }

    func test21StaleLostDoesNotMoveTheCurrentPress() {
        var b = PressBeat(drawn: .earbuds, pressIndex: 5)
        XCTAssertTrue(b.phoneStarted(atMs: 450, forPress: 5))
        XCTAssertFalse(b.phoneFailed(forPress: 4))
        XCTAssertTrue(b.phonePlaying)
        XCTAssertTrue(b.phoneFailed(forPress: 5))
        XCTAssertTrue(b.wristPlays)
    }

    func test21PhoneStopsOnlyItsOwnPress() {
        XCTAssertTrue(Pilot.phoneShouldStop(playingPress: 5, requestedPress: 5))
        XCTAssertFalse(Pilot.phoneShouldStop(playingPress: 6, requestedPress: 5))   // a queued old stop
        XCTAssertFalse(Pilot.phoneShouldStop(playingPress: nil, requestedPress: 5))
    }

    // MARK: 22: the beat start comes from the render timestamp, plus output latency

    func test22FirstClickTimeFromTheRenderTimestamp() {
        // Rendered 4410 frames (0.1 s at 44.1 kHz) by host time 100.5 s: frame 0 at 100.4 s.
        let first = BeatClock.firstFrameHostSeconds(renderHostSeconds: 100.5, playerSampleTime: 4410, sampleRate: 44_100)
        XCTAssertEqual(first, 100.4, accuracy: 1e-9)
        // Now is host 100.6 s = wall 2_000_000 ms; output latency 0.18 s: heard at 1_999_980 ms.
        XCTAssertEqual(BeatClock.wallMs(hostSeconds: first, nowHostSeconds: 100.6, nowWallMs: 2_000_000, latencyS: 0.18), 1_999_980)
    }

    // MARK: 8: earbuds only when the physio confirmed earbuds are paired

    func test8EarbudsNeedThePairedCheck() {
        XCTAssertFalse(Pilot.earbudsAvailable(paired: false, headphoneRoute: true))
        XCTAssertFalse(Pilot.earbudsAvailable(paired: true, headphoneRoute: false))
        XCTAssertTrue(Pilot.earbudsAvailable(paired: true, headphoneRoute: true))
        var s = PilotSettings()
        XCTAssertFalse(s.earbudsPaired)   // "no" until the physio confirms
        s.earbudsPaired = true
        XCTAssertEqual(PilotSettings(dictionary: s.dictionary).earbudsPaired, true)
    }
}
