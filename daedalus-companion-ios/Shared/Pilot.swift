import Foundation

/// Home pilot rules (docs/product/plan.md, B3, B5, B6, B10): where each press plays its beat,
/// when to ask the one-tap check-in, and when to remind the person to pocket their phone.
///
/// A line-for-line port of daedalus_watch_kit/daedalus_watch_kit/pilot.py (pilot-1.0), checked
/// against golden/pilot/pilot.json by `PilotGoldenTest`. The random draw is SplitMix64 with
/// wrapping unsigned 64-bit arithmetic (`&+`, `&*`), so every platform draws the same schedule.
///
/// Every press gets a beat: nothing here ever returns "no beat".
enum Pilot {
    static let version = "pilot-1.0"

    enum Place: String, CaseIterable, Sendable {
        case wrist, earbuds, pocket
    }

    enum CheckIn: String, CaseIterable, Sendable {
        case helped
        case notHelped = "not_helped"
        case alreadyMoving = "already_moving"
        case notFelt = "not_felt"
    }

    /// Fixed order, the same as Python's PLACES.
    static let places: [Place] = [.wrist, .earbuds, .pocket]

    static let learningDays = 7            // days 0-6
    static let pilotDays = 21              // days 7-20 are randomized
    static let maxSeed: Int64 = 1 << 31    // 0 <= seed < 2^31

    static let maxCheckinsPerDay = 6
    static let reminderGapMs: Int64 = 60 * 60 * 1000
    static let phoneCarriedWindowS = 60

    /// B6: in study mode a beat stops by itself after 10 minutes.
    static let studyMaxCueS = 10 * 60
    /// B4: motion kept from 30 s before a press to 60 s after it.
    static let bufferBeforeS = 30
    static let bufferAfterS = 60

    /// iPhone can't play Core Haptics while it is in the background or locked (see HANDOFF-ios.md),
    /// so the pocket place is never available on iPhone: `phone_carried` is always false here.
    static let pocketSupported = false

    private static let gamma: UInt64 = 0x9E37_79B9_7F4A_7C15

    static func splitmix64(_ x: UInt64) -> UInt64 {
        var z = x &+ gamma
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// The 64-bit random number for this person's nth press. Stateless.
    static func draw(seed: Int64, pressIndex: Int64) -> UInt64 {
        splitmix64(UInt64(bitPattern: seed) &+ UInt64(bitPattern: pressIndex) &* gamma)
    }

    static func availablePlaces(earbudsConnected: Bool, phoneCarried: Bool) -> [Place] {
        var out: [Place] = [.wrist]
        if earbudsConnected { out.append(.earbuds) }
        if phoneCarried { out.append(.pocket) }
        return out
    }

    /// Where this press plays its beat. `dayIndex`: whole local calendar days since enrolment.
    /// Out-of-range seeds or negative press indexes are clamped to the wrist rather than throwing:
    /// a press must never fail to play.
    static func choosePlace(seed: Int64, pressIndex: Int64, dayIndex: Int,
                            earbudsConnected: Bool, phoneCarried: Bool) -> Place {
        guard seed >= 0, seed < maxSeed, pressIndex >= 0 else { return .wrist }
        if dayIndex < 0 || dayIndex >= pilotDays { return .wrist }
        let places = availablePlaces(earbudsConnected: earbudsConnected, phoneCarried: phoneCarried)
        let n = UInt64(places.count)
        if dayIndex < learningDays { return places[Int(UInt64(pressIndex) % n)] }
        return places[Int(draw(seed: seed, pressIndex: pressIndex) % n)]
    }

    static func shouldAskCheckin(askedToday: Int) -> Bool { askedToday < maxCheckinsPerDay }

    static func shouldRemindPocket(nowMs: Int64, stoodUp: Bool, phoneStepsLastMin: Int, phoneMovedLastMin: Bool,
                                   lastReminderMs: Int64?, beatPlaying: Bool, enabled: Bool) -> Bool {
        if !enabled || !stoodUp || beatPlaying { return false }
        if phoneStepsLastMin > 0 || phoneMovedLastMin { return false }
        guard let last = lastReminderMs else { return true }
        return nowMs - last >= reminderGapMs
    }

    // MARK: Platform helpers (not in the Python reference)

    /// Whole local calendar days from the enrolment day to `now` (0 on the enrolment day,
    /// negative before it).
    static func dayIndex(enrolment: DateComponents, now: Date, calendar: Calendar = .current) -> Int? {
        guard let enrolDay = calendar.date(from: enrolment) else { return nil }
        let from = calendar.startOfDay(for: enrolDay), to = calendar.startOfDay(for: now)
        return calendar.dateComponents([.day], from: from, to: to).day
    }

    /// Parses "yyyy-MM-dd" (the stored enrolment date) into calendar components.
    static func enrolmentComponents(_ text: String) -> DateComponents? {
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, (1...12).contains(parts[1]), (1...31).contains(parts[2]) else { return nil }
        return DateComponents(year: parts[0], month: parts[1], day: parts[2])
    }

    static func enrolmentText(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// The place that actually plays. A place the watch can't reach (the iPhone didn't confirm the
    /// beat in time, earbuds dropped, the phone is out of range) falls back to the wrist at once.
    /// Never silence: the answer is always a place that plays.
    static func placeToPlay(drawn: Place, phoneConfirmed: Bool) -> Place {
        switch drawn {
        case .wrist: return .wrist
        case .earbuds, .pocket: return phoneConfirmed ? drawn : .wrist
        }
    }

    /// The beat start on the watch's clock from the iPhone's reply (NTP-style offset from one
    /// round trip). `t0`/`t1`: the watch's send and receive times; `r0`/`r1`: the iPhone's receive
    /// and reply times; `beatStartPhoneMs`: when the first click plays, on the iPhone's clock.
    static func beatStartOnWatchClock(t0: Int64, t1: Int64, r0: Int64, r1: Int64, beatStartPhoneMs: Int64) -> Int64 {
        let offset = ((r0 - t0) + (r1 - t1)) / 2   // phone clock minus watch clock
        return beatStartPhoneMs - offset
    }
}

/// The click schedule of the iPhone's sound beat (B1): one period of audio (a click, then silence),
/// looped by the audio player, so the beat keeps the audio clock and doesn't drift with the app.
enum BeatClock {
    /// Frames in one beat period at this pace (rounded to a whole frame, as the loop must be).
    static func periodFrames(sampleRate: Double, paceSpm: Int) -> Int {
        Int((sampleRate * 60.0 / Double(max(paceSpm, 1))).rounded())
    }

    /// When beat `n` plays, in seconds after the first beat, for a looped buffer of `periodFrames`.
    static func beatTime(_ n: Int, sampleRate: Double, paceSpm: Int) -> Double {
        Double(n * periodFrames(sampleRate: sampleRate, paceSpm: paceSpm)) / sampleRate
    }

    /// The largest gap, in ms, between the looped schedule and the ideal pace over `seconds`.
    static func maxDriftMs(seconds: Double, sampleRate: Double, paceSpm: Int) -> Double {
        let ideal = 60.0 / Double(max(paceSpm, 1))
        let beats = Int(seconds / ideal)
        var worst = 0.0
        for n in 0...beats {
            worst = max(worst, abs(beatTime(n, sampleRate: sampleRate, paceSpm: paceSpm) - Double(n) * ideal) * 1000)
        }
        return worst
    }
}

/// Pilot settings, set on the iPhone (Profile → Home pilot) and sent to the watch in the
/// application context under `WatchKey.pilot`.
struct PilotSettings: Equatable, Sendable {
    var enabled = false
    var participant = ""
    var seed: Int64 = 0
    /// "yyyy-MM-dd", local calendar.
    var enrolment = ""
    var pocketReminder = true
    /// Reminder times as minutes after midnight (Apple Watch has no stand-up detector).
    var reminderMinutes: [Int] = []

    /// Study mode runs only when the switch is on and the settings are complete.
    var isActive: Bool {
        enabled && !participant.trimmingCharacters(in: .whitespaces).isEmpty
            && seed >= 0 && seed < Pilot.maxSeed && Pilot.enrolmentComponents(enrolment) != nil
    }

    var dictionary: [String: Any] {
        ["enabled": enabled, "participant": participant, "seed": NSNumber(value: seed), "enrolment": enrolment,
         "pocketReminder": pocketReminder, "reminderMinutes": reminderMinutes]
    }

    init() {}

    init(dictionary d: [String: Any]) {
        enabled = d["enabled"] as? Bool ?? false
        participant = d["participant"] as? String ?? ""
        seed = (d["seed"] as? NSNumber)?.int64Value ?? 0
        enrolment = d["enrolment"] as? String ?? ""
        pocketReminder = d["pocketReminder"] as? Bool ?? true
        reminderMinutes = (d["reminderMinutes"] as? [Int]) ?? []
    }

    /// Settings that change the schedule (a new person or seed) restart the press counter.
    var scheduleIdentity: String { "\(participant)|\(seed)|\(enrolment)" }
}
