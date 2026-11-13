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

    // MARK: Round 001 rules

    /// 17: the iPhone's beat stops by itself after the study limit, even if the watch's stop never arrives.
    static let phoneBeatLimitS = studyMaxCueS

    /// 21: a stop (sent now, or queued and delivered late) stops the iPhone's beat only when it is
    /// for the press that beat belongs to.
    static func phoneShouldStop(playingPress: Int64?, requestedPress: Int64) -> Bool {
        playingPress == requestedPress
    }

    /// 8: iOS can't tell earbuds from a Bluetooth speaker, so earbuds count only when the physio
    /// confirmed at set-up that the person's earbuds are paired with this iPhone.
    static func earbudsAvailable(paired: Bool, headphoneRoute: Bool) -> Bool { paired && headphoneRoute }

    /// 18, 19: how long the watch waits for the background recorder to reach press + 60 s before
    /// writing what it has, marked `motion_partial`.
    static let sealGiveUpMs: Int64 = 10 * 60 * 1000

    enum SealDecision: Equatable, Sendable { case wait, seal(partial: Bool) }

    // MARK: Round 002 rules

    /// 15: a study press records its motion only into the press file. It writes no ordinary
    /// session CSV, which would reach the iPhone's session list and cloud backup.
    static func writesSessionCsv(studyPress: Bool) -> Bool { !studyPress }

    /// 10: the background retry that writes waiting press files doesn't run while a press runs.
    static func sealRetryShouldRun(pressRunning: Bool) -> Bool { !pressRunning }

    /// When a waiting press file can be written. The press still running is never written (its
    /// file is updated when it ends); an orphan (the app was stopped mid-beat) is.
    static func sealDecision(nowMs: Int64, pressMs: Int64, recordedLastMs: Int64?, isCurrentPress: Bool) -> SealDecision {
        if isCurrentPress { return .wait }
        let end = pressMs + Int64(bufferAfterS) * 1000
        if nowMs < end { return .wait }
        // 100 ms of slack: the last 50 Hz sample can fall just short of the window's end.
        if let last = recordedLastMs, last >= end - 100 { return .seal(partial: false) }
        return nowMs >= pressMs + sealGiveUpMs ? .seal(partial: true) : .wait
    }
}

/// Pilot files on disk (15, 20): folders excluded from backup, and writes that never replace an
/// existing file (a clash keeps both, the new one as `<name>_2.json`, `_3`, ...).
enum PilotFiles {
    @discardableResult
    static func directory(_ url: URL) -> URL {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var u = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? u.setResourceValues(values)
        return url
    }

    private static func candidate(_ dir: URL, _ name: String, _ n: Int) -> URL {
        guard n > 1 else { return dir.appendingPathComponent(name) }
        let u = URL(fileURLWithPath: name)
        let ext = u.pathExtension.isEmpty ? "" : ".\(u.pathExtension)"
        return dir.appendingPathComponent("\(u.deletingPathExtension().lastPathComponent)_\(n)\(ext)")
    }

    /// Writes `data` as `name` in `dir`, or the next free `_n` name. Never overwrites.
    static func writeNew(_ data: Data, in dir: URL, name: String) throws -> URL {
        for n in 1...1000 {
            let url = candidate(dir, name, n)
            do {
                try data.write(to: url, options: .withoutOverwriting)
                return url
            } catch CocoaError.fileWriteFileExists {
                continue
            }
        }
        throw CocoaError(.fileWriteFileExists)
    }

    /// Moves `file` into `dir` as `name`, or the next free `_n` name. Never replaces.
    static func moveNew(_ file: URL, into dir: URL, name: String) throws -> URL {
        for n in 1...1000 {
            let url = candidate(dir, name, n)
            if FileManager.default.fileExists(atPath: url.path) {
                // Round 002, 14: the same file delivered twice (identical bytes) is kept once.
                if FileManager.default.contentsEqual(atPath: url.path, andPath: file.path) {
                    try? FileManager.default.removeItem(at: file)
                    return url
                }
                continue
            }
            do {
                try FileManager.default.moveItem(at: file, to: url)
                return url
            } catch CocoaError.fileWriteFileExists {
                continue
            }
        }
        throw CocoaError(.fileWriteFileExists)
    }
}

/// The beat of one study press: which place plays it, whether the wrist must play, and when it
/// started. The watch drives it from the iPhone's answers; every path ends with a place playing.
/// The only moment nothing plays is while a phone place waits for the iPhone's confirmation, and
/// the watch ends that wait with `phoneFailed()` after `PilotController.phoneConfirmTimeout`.
struct PressBeat: Equatable, Sendable {
    private(set) var place: Pilot.Place
    private(set) var wristPlays: Bool
    private(set) var phonePlaying = false
    private(set) var beatStartMs: Int64?
    private(set) var stopped = false
    /// 21: the press this beat belongs to; messages from the iPhone about another press are ignored.
    let pressIndex: Int64

    init(drawn: Pilot.Place, pressIndex: Int64 = 0) {
        place = drawn
        wristPlays = drawn == .wrist
        self.pressIndex = pressIndex
    }

    /// Nothing plays yet: a phone place waiting for the iPhone.
    var waitingForPhone: Bool { !stopped && !wristPlays && !phonePlaying }

    /// The iPhone confirmed its first click at `ms` (watch clock). False when it's too late (the
    /// wrist already took over, or the press stopped) or the reply is for another press: the
    /// caller then stops the iPhone's beat for that press.
    mutating func phoneStarted(atMs ms: Int64, forPress press: Int64? = nil) -> Bool {
        if let press, press != pressIndex { return false }
        guard !stopped, !wristPlays, place != .wrist else { return false }
        phonePlaying = true
        beatStartMs = ms
        return true
    }

    /// The iPhone couldn't start the beat, didn't confirm in time, or lost it (earbuds removed).
    /// True when the wrist must start now. A beat that never started on the iPhone is a wrist
    /// press; one that started there keeps that place and its start time. `forPress`: the press
    /// an iPhone message is about (nil for the watch's own timeout); another press is ignored.
    mutating func phoneFailed(forPress press: Int64? = nil) -> Bool {
        if let press, press != pressIndex { return false }
        guard !stopped, !wristPlays else { return false }
        if beatStartMs == nil { place = Pilot.placeToPlay(drawn: place, phoneConfirmed: false) }
        phonePlaying = false
        wristPlays = true
        return true
    }

    /// The pending press file has been saved once (`PilotController.pressStarted`).
    private(set) var pendingSaved = false

    mutating func pendingWasSaved() { pendingSaved = true }

    /// A wrist pulse played: the first one is the beat start of a wrist press. True when this
    /// pulse set the start after the pending file was first saved without one, that is, the
    /// fallback case (round 002 fix 12, narrowed in round 003 fix 9): the caller saves then. A
    /// direct wrist press sets its start before that first save, which already carries it.
    @discardableResult
    mutating func wristPulse(atMs ms: Int64) -> Bool {
        guard wristPlays && beatStartMs == nil else { return false }
        beatStartMs = ms
        return pendingSaved
    }

    mutating func stop() {
        stopped = true
        phonePlaying = false
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

    /// 22: host time (s) at which the player's frame 0 was rendered, from a render timestamp
    /// (`lastRenderTime`) and the player's sample time at that render.
    static func firstFrameHostSeconds(renderHostSeconds: Double, playerSampleTime: Double, sampleRate: Double) -> Double {
        renderHostSeconds - playerSampleTime / sampleRate
    }

    /// Wall-clock ms of a host time, plus the output latency (the time from render to the ear).
    static func wallMs(hostSeconds: Double, nowHostSeconds: Double, nowWallMs: Int64, latencyS: Double) -> Int64 {
        nowWallMs + Int64(((hostSeconds - nowHostSeconds + latencyS) * 1000).rounded())
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
    /// 8: the physio confirmed at set-up that the person's earbuds are paired with this iPhone.
    /// "No" (the default) means the earbuds place is never drawn.
    var earbudsPaired = false

    /// 16: the code, seed, start date and earbuds check can't change while study mode is on.
    var fieldsLocked: Bool { enabled }

    /// Round 002, 9: the switch is on but the saved settings aren't complete (for example saved
    /// by an earlier build): the pilot screen says so.
    var statusMessageKey: String? { enabled && !isActive ? "pilot_incomplete" : nil }

    /// Study mode runs only when the switch is on and the settings are complete.
    var isActive: Bool {
        enabled && !participant.trimmingCharacters(in: .whitespaces).isEmpty
            && seed >= 0 && seed < Pilot.maxSeed && Pilot.enrolmentComponents(enrolment) != nil
    }

    var dictionary: [String: Any] {
        ["enabled": enabled, "participant": participant, "seed": NSNumber(value: seed), "enrolment": enrolment,
         "pocketReminder": pocketReminder, "reminderMinutes": reminderMinutes, "earbudsPaired": earbudsPaired]
    }

    init() {}

    init(dictionary d: [String: Any]) {
        enabled = d["enabled"] as? Bool ?? false
        participant = d["participant"] as? String ?? ""
        seed = (d["seed"] as? NSNumber)?.int64Value ?? 0
        enrolment = d["enrolment"] as? String ?? ""
        pocketReminder = d["pocketReminder"] as? Bool ?? true
        reminderMinutes = (d["reminderMinutes"] as? [Int]) ?? []
        earbudsPaired = d["earbudsPaired"] as? Bool ?? false
    }

    /// Settings that change the schedule (a new person or seed) restart the press counter.
    var scheduleIdentity: String { "\(participant)|\(seed)|\(enrolment)" }
}

/// 16: what the physio types on the iPhone's pilot screen, applied only by Save and only when
/// every field is valid, so an invalid seed is never stored or sent to the watch.
struct PilotDraft: Equatable, Sendable {
    var participant: String
    var seedText: String
    var enrolment: String
    var earbudsPaired: Bool

    init(_ s: PilotSettings) {
        participant = s.participant
        seedText = String(s.seed)
        enrolment = s.enrolment
        earbudsPaired = s.earbudsPaired
    }

    /// 0 <= seed < 2^31, digits only.
    static func seed(_ text: String) -> Int64? {
        guard !text.isEmpty, text.allSatisfy({ $0.isASCII && $0.isNumber }), let v = Int64(text),
              v >= 0, v < Pilot.maxSeed else { return nil }
        return v
    }

    /// Round 002, 9: when study mode turns on or off, unsaved edits are dropped and the fields
    /// show the saved settings (which are what the watch runs with).
    func afterStudyModeChange(to saved: PilotSettings) -> PilotDraft { PilotDraft(saved) }

    /// `base` with this draft's fields, or nil when the code, seed or date isn't valid.
    func applied(to base: PilotSettings) -> PilotSettings? {
        let code = participant.trimmingCharacters(in: .whitespaces)
        guard !code.isEmpty, let seed = Self.seed(seedText), Pilot.enrolmentComponents(enrolment) != nil else { return nil }
        var s = base
        s.participant = code
        s.seed = seed
        s.enrolment = enrolment
        s.earbudsPaired = earbudsPaired
        return s
    }
}
