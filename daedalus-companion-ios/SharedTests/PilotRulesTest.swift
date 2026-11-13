import Foundation
import XCTest

/// Home-pilot rules beyond the golden rows: never silence, shares of the draw, the check-in limit,
/// the hourly reminder, the day index, the clock offset and the sound-beat schedule.
final class PilotRulesTest: XCTestCase {

    // MARK: Never silence (B3)

    func testEveryPressGetsAPlaceThatIsAvailable() {
        for seed: Int64 in [0, 1, 12_345, Pilot.maxSeed - 1] {
            for press: Int64 in 0..<300 {
                for day in -2...25 {
                    for ear in [false, true] {
                        for carried in [false, true] {
                            let p = Pilot.choosePlace(seed: seed, pressIndex: press, dayIndex: day,
                                                      earbudsConnected: ear, phoneCarried: carried)
                            XCTAssertTrue(Pilot.availablePlaces(earbudsConnected: ear, phoneCarried: carried).contains(p))
                        }
                    }
                }
            }
        }
    }

    func testUnavailablePhonePlacesFallBackToTheWrist() {
        XCTAssertEqual(Pilot.placeToPlay(drawn: .earbuds, phoneConfirmed: false), .wrist)
        XCTAssertEqual(Pilot.placeToPlay(drawn: .pocket, phoneConfirmed: false), .wrist)
        XCTAssertEqual(Pilot.placeToPlay(drawn: .wrist, phoneConfirmed: false), .wrist)
        XCTAssertEqual(Pilot.placeToPlay(drawn: .earbuds, phoneConfirmed: true), .earbuds)
        // Every combination plays somewhere: the result is always one of the three places.
        for drawn in Pilot.Place.allCases {
            for ok in [false, true] { XCTAssertTrue(Pilot.places.contains(Pilot.placeToPlay(drawn: drawn, phoneConfirmed: ok))) }
        }
    }

    func testWristPressPlaysAtOnce() {
        var b = PressBeat(drawn: .wrist)
        XCTAssertTrue(b.wristPlays)
        XCTAssertFalse(b.waitingForPhone)
        b.wristPulse(atMs: 500); b.wristPulse(atMs: 1100)
        XCTAssertEqual(b.beatStartMs, 500)
        XCTAssertFalse(b.phoneStarted(atMs: 700))   // a stray iPhone reply never silences the wrist
        XCTAssertTrue(b.wristPlays)
    }

    func testEarbudsThatNeverConfirmBecomeAWristPress() {
        var b = PressBeat(drawn: .earbuds)
        XCTAssertTrue(b.waitingForPhone)
        XCTAssertTrue(b.phoneFailed())               // no reply in time, or out of reach
        XCTAssertTrue(b.wristPlays)
        XCTAssertEqual(b.place, .wrist)
        b.wristPulse(atMs: 1_000)
        XCTAssertEqual(b.beatStartMs, 1_000)
        XCTAssertFalse(b.phoneStarted(atMs: 1_200))  // the late reply is turned away (the iPhone is stopped)
        XCTAssertEqual(b.beatStartMs, 1_000)
    }

    /// Brief rule: `beat_start_ms` stays null when the beat start was never confirmed (Stop during
    /// the wait for the iPhone). It is never filled with the press time.
    func testUnconfirmedBeatStartStaysNull() {
        var b = PressBeat(drawn: .earbuds)
        b.wristPulse(atMs: 300)   // silent wrist ticks don't count as a start
        b.stop()
        XCTAssertNil(b.beatStartMs)
        XCTAssertFalse(b.phoneStarted(atMs: 900))   // a reply after Stop doesn't fill it either
        XCTAssertNil(b.beatStartMs)
    }

    func testEarbudsLostMidBeatHandOverToTheWristAndKeepTheirStart() {
        var b = PressBeat(drawn: .earbuds)
        XCTAssertTrue(b.phoneStarted(atMs: 400))
        XCTAssertFalse(b.wristPlays)
        b.wristPulse(atMs: 450)                      // (silent wrist ticks don't move the start)
        XCTAssertTrue(b.phoneFailed())               // earbuds removed
        XCTAssertTrue(b.wristPlays)
        XCTAssertEqual(b.place, .earbuds)
        XCTAssertEqual(b.beatStartMs, 400)
    }

    /// Never silence: every sequence of iPhone answers, ended by the confirmation timeout (which
    /// the watch always fires), leaves a place playing until the press stops.
    func testNoSequenceOfAnswersLeavesSilence() {
        enum Event: CaseIterable { case confirmed, failed, lost, pulse }
        func sequences(_ n: Int) -> [[Event]] {
            n == 0 ? [[]] : sequences(n - 1).flatMap { s in Event.allCases.map { s + [$0] } }
        }
        for drawn in Pilot.Place.allCases {
            for seq in (0...4).flatMap(sequences) {
                var b = PressBeat(drawn: drawn)
                var t: Int64 = 0
                for e in seq {
                    t += 100
                    switch e {
                    case .confirmed: _ = b.phoneStarted(atMs: t)
                    case .failed, .lost: _ = b.phoneFailed()
                    case .pulse: b.wristPulse(atMs: t)
                    }
                }
                if b.waitingForPhone { _ = b.phoneFailed() }   // the timeout
                XCTAssertTrue(b.wristPlays || b.phonePlaying, "\(drawn) \(seq)")
                XCTAssertTrue(Pilot.places.contains(b.place))
            }
        }
    }

    func testBadInputsStillGiveTheWrist() {
        XCTAssertEqual(Pilot.choosePlace(seed: -1, pressIndex: 0, dayIndex: 10, earbudsConnected: true, phoneCarried: true), .wrist)
        XCTAssertEqual(Pilot.choosePlace(seed: Pilot.maxSeed, pressIndex: 0, dayIndex: 10, earbudsConnected: true, phoneCarried: true), .wrist)
        XCTAssertEqual(Pilot.choosePlace(seed: 3, pressIndex: -5, dayIndex: 10, earbudsConnected: true, phoneCarried: true), .wrist)
    }

    func testIPhoneNeverOffersThePocket() {
        XCTAssertFalse(Pilot.pocketSupported)
        for press: Int64 in 0..<500 {
            XCTAssertNotEqual(Pilot.choosePlace(seed: 7, pressIndex: press, dayIndex: 12, earbudsConnected: true,
                                                phoneCarried: Pilot.pocketSupported), .pocket)
        }
    }

    /// Plan B3: over 1,000 simulated presses each available place is drawn in equal shares within 3%.
    func testRandomizedDaysDrawEqualShares() {
        for (ear, carried) in [(true, false), (true, true)] {
            let places = Pilot.availablePlaces(earbudsConnected: ear, phoneCarried: carried)
            var counts: [Pilot.Place: Int] = [:]
            let n = 3000
            for press in 0..<n {
                counts[Pilot.choosePlace(seed: 4242, pressIndex: Int64(press), dayIndex: 10,
                                         earbudsConnected: ear, phoneCarried: carried), default: 0] += 1
            }
            for p in places {
                XCTAssertEqual(Double(counts[p] ?? 0) / Double(n), 1.0 / Double(places.count), accuracy: 0.03, "\(p)")
            }
        }
    }

    // MARK: Check-in (B5)

    func testSeventhPressOfTheDayDoesNotAsk() {
        let asks = (0..<8).map { Pilot.shouldAskCheckin(askedToday: $0) }
        XCTAssertEqual(asks, [true, true, true, true, true, true, false, false])
    }

    // MARK: Pocket reminder (B10)

    func testReminderAtMostHourly() {
        let hour = Pilot.reminderGapMs
        func remind(_ now: Int64, last: Int64?, steps: Int = 0, beat: Bool = false, on: Bool = true) -> Bool {
            Pilot.shouldRemindPocket(nowMs: now, stoodUp: true, phoneStepsLastMin: steps, phoneMovedLastMin: false,
                                     lastReminderMs: last, beatPlaying: beat, enabled: on)
        }
        XCTAssertTrue(remind(10 * hour, last: nil))
        XCTAssertFalse(remind(10 * hour, last: 10 * hour - hour + 1))
        XCTAssertTrue(remind(10 * hour, last: 9 * hour))
        XCTAssertFalse(remind(10 * hour, last: nil, steps: 12))   // the phone walked along
        XCTAssertFalse(remind(10 * hour, last: nil, beat: true))  // never during a beat
        XCTAssertFalse(remind(10 * hour, last: nil, on: false))   // off with one setting
    }

    // MARK: Day index

    func testDayIndexIsWholeLocalCalendarDays() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/Toronto")!
        let enrol = Pilot.enrolmentComponents("2026-11-01")!
        func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int, _ min: Int = 0) -> Date {
            cal.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
        }
        XCTAssertEqual(Pilot.dayIndex(enrolment: enrol, now: at(2026, 11, 1, 0, 1), calendar: cal), 0)
        XCTAssertEqual(Pilot.dayIndex(enrolment: enrol, now: at(2026, 11, 1, 23, 59), calendar: cal), 0)
        XCTAssertEqual(Pilot.dayIndex(enrolment: enrol, now: at(2026, 11, 2, 0, 1), calendar: cal), 1)
        // 2026-11-01 is a DST change in Toronto: still whole calendar days, not 24 h blocks.
        XCTAssertEqual(Pilot.dayIndex(enrolment: enrol, now: at(2026, 11, 8, 0, 30), calendar: cal), 7)
        XCTAssertEqual(Pilot.dayIndex(enrolment: enrol, now: at(2026, 10, 31, 12), calendar: cal), -1)
        XCTAssertNil(Pilot.enrolmentComponents("2026-13-01"))
        XCTAssertEqual(Pilot.enrolmentText(at(2026, 3, 4, 12), calendar: cal), "2026-03-04")
    }

    // MARK: Settings

    func testSettingsRoundTripAndActivation() {
        var s = PilotSettings()
        XCTAssertFalse(s.isActive)   // off by default
        s.enabled = true; s.participant = "P03"; s.seed = 123_456; s.enrolment = "2026-11-13"; s.reminderMinutes = [540, 900]
        XCTAssertTrue(s.isActive)
        XCTAssertEqual(PilotSettings(dictionary: s.dictionary), s)
        s.seed = Pilot.maxSeed
        XCTAssertFalse(s.isActive)
    }

    // MARK: Clocks and the sound beat (B1)

    func testBeatStartIsMovedToTheWatchClock() {
        // Phone clock is 250 ms ahead; 40 ms each way; the first click plays 120 ms after the reply.
        let t0: Int64 = 1_000_000, skew: Int64 = 250
        let r0 = t0 + 40 + skew, r1 = r0 + 10, t1 = r1 - skew + 40
        let start = Pilot.beatStartOnWatchClock(t0: t0, t1: t1, r0: r0, r1: r1, beatStartPhoneMs: r1 + 120)
        XCTAssertEqual(start, r1 + 120 - skew)
    }

    func testSoundBeatDriftsLessThan20MsOverTwoMinutes() {
        for rate in [44_100.0, 48_000.0] {
            for pace in [60, 80, 97, 104, 113, 140] {
                XCTAssertLessThan(BeatClock.maxDriftMs(seconds: 120, sampleRate: rate, paceSpm: pace), 20, "\(rate) \(pace)")
            }
        }
    }
}
