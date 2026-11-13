import CoreMotion
import Foundation
import Observation
import UserNotifications
import WatchConnectivity
import WatchKit

/// Home pilot on the watch (docs/product/plan.md B1, B3–B6, B10), active only while the study
/// switch on the iPhone is on and the pilot settings are complete. With it off, nothing here runs
/// and `CueController` behaves as before.
///
/// Per press: draw the place (`Pilot.choosePlace`), start the beat there, fall back to the wrist at
/// once if the iPhone can't play it, record when the beat really started and stopped, ask the
/// one-tap check-in (at most 6 a day), then write one press file with the motion from 30 s before
/// to 60 s after the press, and send it to the iPhone.
@MainActor
@Observable
final class PilotController {
    static let shared = PilotController()

    private(set) var settings: PilotSettings
    /// The place the running press plays its beat in (shown small on the walking screen).
    private(set) var currentPlace: Pilot.Place?
    /// The beat stopped and the check-in is waiting for an answer.
    private(set) var checkinPending = false

    /// How long the iPhone has to confirm the sound beat before the wrist takes over.
    static let phoneConfirmTimeout: Duration = .milliseconds(1000)
    /// How long the check-in stays up without an answer (the answer is then null).
    static let checkinTimeout: Duration = .seconds(30)
    /// A cached "earbuds connected" from the iPhone counts for this long.
    static let earbudsFresh: TimeInterval = 5 * 60
    /// The press file is written once the 60 s after the press are over, plus a margin for the
    /// background recorder to catch up.
    static let sealDelayMs: Int64 = Int64(Pilot.bufferAfterS + 30) * 1000

    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private var press: PressRecord?
    /// The running press's beat (place, who plays, start time); nil between presses.
    @ObservationIgnored private var beat: PressBeat?
    @ObservationIgnored private var fallbackTask: Task<Void, Never>?
    @ObservationIgnored private var checkinTask: Task<Void, Never>?
    @ObservationIgnored private var earbuds: (connected: Bool, at: Date)?
    @ObservationIgnored let live = LiveAccRing()
    @ObservationIgnored private let sensorRecorder = CMSensorRecorder()

    var isStudy: Bool { settings.isActive }

    private init() {
        settings = PilotSettings(dictionary: UserDefaults.standard.dictionary(forKey: WatchKey.pilot) ?? [:])
    }

    // MARK: Settings (from the iPhone)

    func apply(settings new: PilotSettings) {
        guard new != settings else { return }
        if new.scheduleIdentity != settings.scheduleIdentity {
            // A new person, seed or enrolment day: the press counter starts again at 0.
            defaults.set(0, forKey: "pilotPressIndex")
        }
        settings = new
        defaults.set(new.dictionary, forKey: WatchKey.pilot)
        if isStudy {
            armRecorder()
            if new.pocketReminder && !new.reminderMinutes.isEmpty {
                UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
            }
        }
        scheduleNextReminder()
    }

    /// When the app comes to the front: keep the background recorder running, refresh the
    /// iPhone's earbuds state, and write press files whose motion is complete.
    func appBecameActive() {
        guard isStudy else { return }
        armRecorder()
        refreshEarbuds()
        sealDuePresses()
        scheduleNextReminder()
    }

    // MARK: Background accelerometer (B4)

    /// CMSensorRecorder keeps 50 Hz acceleration even while the app is suspended (up to 12 h a call,
    /// kept 3 days), which is what supplies the 30 s before a press. Re-armed every 6 h of use.
    private func armRecorder() {
        guard CMSensorRecorder.isAccelerometerRecordingAvailable() else { return }
        let last = defaults.object(forKey: "pilotRecorderArmedAt") as? Date ?? .distantPast
        guard Date.now.timeIntervalSince(last) > 6 * 3600 else { return }
        sensorRecorder.recordAccelerometer(forDuration: 12 * 3600)
        defaults.set(Date.now, forKey: "pilotRecorderArmedAt")
    }

    // MARK: Earbuds state (from the iPhone)

    func refreshEarbuds() {
        let session = WCSession.default
        guard isStudy, session.activationState == .activated, session.isReachable else { return }
        session.sendMessage([WatchKey.command: WatchKey.pilotQuery], replyHandler: { reply in
            let connected = reply[WatchKey.earbuds] as? Bool ?? false
            Task { @MainActor in PilotController.shared.earbuds = (connected, .now) }
        }, errorHandler: nil)
    }

    /// The iPhone pushes its earbuds state when the audio route changes.
    func phoneReported(earbuds connected: Bool) { earbuds = (connected, .now) }

    private var earbudsConnected: Bool {
        guard let earbuds, Date.now.timeIntervalSince(earbuds.at) < Self.earbudsFresh else { return false }
        return earbuds.connected && WCSession.default.isReachable
    }

    // MARK: A press

    /// A study press ("I'm stuck" on the watch): draws the place and returns whether the wrist plays.
    /// Earbuds: the wrist stays silent while the iPhone confirms; if it doesn't within
    /// `phoneConfirmTimeout`, `CueController.fallBackToWrist()` starts the wrist beat.
    func beginPress(pace: Int) -> Bool {
        // A check-in still up from the last press closes unanswered, so its file is kept.
        if checkinPending { answer(nil) }
        let now = Date.now
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let index = Int64(defaults.integer(forKey: "pilotPressIndex"))
        defaults.set(Int(index + 1), forKey: "pilotPressIndex")
        let day = Pilot.enrolmentComponents(settings.enrolment).flatMap { Pilot.dayIndex(enrolment: $0, now: now) } ?? -1
        let ear = earbudsConnected
        let carried = Pilot.pocketSupported   // always false on iPhone: see Pilot.pocketSupported
        let drawn = Pilot.choosePlace(seed: settings.seed, pressIndex: index, dayIndex: day,
                                      earbudsConnected: ear, phoneCarried: carried)
        press = PressRecord(participant: settings.participant, pressIndex: index, dayIndex: day, place: drawn,
                            earbudsConnected: ear, phoneCarried: carried, paceSpm: pace, pressMs: nowMs)
        beat = PressBeat(drawn: drawn)
        currentPlace = drawn
        armRecorder()
        if drawn != .wrist { startPhoneBeat(pace: pace) }
        // startPhoneBeat may already have fallen back (iPhone out of reach): then the wrist plays now.
        return beat?.wristPlays ?? true
    }

    private func startPhoneBeat(pace: Int) {
        let t0 = Self.nowMs()
        let session = WCSession.default
        fallbackTask?.cancel()
        fallbackTask = Task { [weak self] in
            try? await Task.sleep(for: Self.phoneConfirmTimeout)
            guard !Task.isCancelled else { return }
            self?.fallBack()
        }
        // The iPhone is out of reach: the wrist plays now, with no wait.
        guard session.activationState == .activated, session.isReachable else { fallBack(); return }
        let message: [String: Any] = [WatchKey.command: WatchKey.beatStart, WatchKey.rate: pace]
        session.sendMessage(message, replyHandler: { reply in
            let t1 = Self.nowMs()
            Task { @MainActor in PilotController.shared.phoneReplied(reply, t0: t0, t1: t1) }
        }, errorHandler: { _ in
            Task { @MainActor in PilotController.shared.fallBack() }
        })
    }

    private func phoneReplied(_ reply: [String: Any], t0: Int64, t1: Int64) {
        guard reply[WatchKey.ok] as? Bool == true,
              let start = (reply[WatchKey.beatStartMs] as? NSNumber)?.int64Value,
              let r0 = (reply[WatchKey.r0] as? NSNumber)?.int64Value,
              let r1 = (reply[WatchKey.r1] as? NSNumber)?.int64Value
        else { fallBack(); return }
        let atMs = Pilot.beatStartOnWatchClock(t0: t0, t1: t1, r0: r0, r1: r1, beatStartPhoneMs: start)
        guard var beat, beat.phoneStarted(atMs: atMs) else {
            // Too late: the wrist already took over, or the press ended. Silence the iPhone.
            sendBeatStop()
            return
        }
        fallbackTask?.cancel(); fallbackTask = nil
        self.beat = beat
    }

    /// The iPhone couldn't start or keep the beat: the wrist plays at once. A press whose beat
    /// never started on the iPhone is recorded as a wrist press; one that lost its earbuds midway
    /// keeps the place where its beat started.
    func fallBack() {
        fallbackTask?.cancel(); fallbackTask = nil
        guard var beat, beat.phoneFailed() else { return }
        self.beat = beat
        currentPlace = beat.place
        sendBeatStop()
        CueController.shared.fallBackToWrist()
    }

    /// A wrist pulse of this press played (the first is the wrist's beat start).
    func wristBeatPlayed(atMs ms: Int64) {
        beat?.wristPulse(atMs: ms)
    }

    /// The beat stopped (Stop, or the 10-minute limit): ask the check-in, or write the file.
    func endPress() {
        guard var press, var beat, !checkinPending else { return }
        fallbackTask?.cancel(); fallbackTask = nil
        let phoneWasAsked = beat.place != .wrist || beat.phonePlaying
        beat.stop()
        self.beat = nil
        press.place = beat.place
        press.beatStartMs = beat.beatStartMs
        press.beatStopMs = Self.nowMs()
        self.press = press
        if phoneWasAsked { sendBeatStop() }
        currentPlace = nil
        let today = Pilot.enrolmentText(.now)
        if defaults.string(forKey: "pilotCheckinDay") != today {
            defaults.set(today, forKey: "pilotCheckinDay")
            defaults.set(0, forKey: "pilotCheckinCount")
        }
        let asked = defaults.integer(forKey: "pilotCheckinCount")
        guard Pilot.shouldAskCheckin(askedToday: asked) else { finishPress(checkin: nil); return }
        defaults.set(asked + 1, forKey: "pilotCheckinCount")
        checkinPending = true
        checkinTask = Task { [weak self] in
            try? await Task.sleep(for: Self.checkinTimeout)
            guard !Task.isCancelled else { return }
            self?.answer(nil)
        }
    }

    /// One tap on the check-in, or nil when it timed out.
    func answer(_ checkin: Pilot.CheckIn?) {
        guard checkinPending else { return }
        checkinTask?.cancel(); checkinTask = nil
        checkinPending = false
        finishPress(checkin: checkin)
    }

    private func finishPress(checkin: Pilot.CheckIn?) {
        guard var press else { return }
        press.checkin = checkin
        self.press = nil
        currentPlace = nil
        PressStore.savePending(press, live: live.snapshot())
        sealDuePresses()
        let due = press.pressMs + Self.sealDelayMs - Self.nowMs()
        if due > 0 {
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(due + 500))
                self?.sealDuePresses()
            }
        }
    }

    private func sendBeatStop() {
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return }
        session.sendMessage([WatchKey.command: WatchKey.beatStop], replyHandler: nil)
    }

    /// Writes every waiting press whose 60 s after are over, and sends it to the iPhone.
    func sealDuePresses() {
        let now = Self.nowMs()
        for (url, record, liveSamples) in PressStore.pending() where now >= record.pressMs + Self.sealDelayMs {
            let from = Date(timeIntervalSince1970: Double(record.pressMs - Int64(Pilot.bufferBeforeS) * 1000) / 1000)
            let to = Date(timeIntervalSince1970: Double(record.pressMs + Int64(Pilot.bufferAfterS) * 1000) / 1000)
            var full = record
            full.samples = PressSamples.merge(recorded: Self.recorded(from: from, to: to), live: liveSamples, pressMs: record.pressMs)
            if let file = PressStore.write(full) {
                try? FileManager.default.removeItem(at: url)
                PhoneLink.shared.send(pilotFile: file)
            }
        }
    }

    /// The background recorder's acceleration in [from, to], in m/s² with wall-clock ms.
    private static func recorded(from: Date, to: Date) -> [PressSamples.Sample] {
        guard CMSensorRecorder.isAccelerometerRecordingAvailable(),
              let list = CMSensorRecorder().accelerometerData(from: from, to: to) else { return [] }
        var out: [PressSamples.Sample] = []
        for case let d as CMRecordedAccelerometerData in IteratorSequence(NSFastEnumerationIterator(list)) {
            let a = d.acceleration, g = 9.80665
            out.append(.init(tMs: Int64((d.startDate.timeIntervalSince1970 * 1000).rounded()), ax: a.x * g, ay: a.y * g, az: a.z * g))
        }
        return out
    }

    static func nowMs() -> Int64 { Int64(Date.now.timeIntervalSince1970 * 1000) }

    // MARK: Pocket reminder (B10, fallback: times set on the iPhone)

    /// Apple Watch has no background stand-up trigger, so the reminder runs at the times the
    /// person set: a background refresh near each time asks the iPhone for its steps in the last
    /// minute and applies `Pilot.shouldRemindPocket` (with the stand-up taken as given).
    func scheduleNextReminder() {
        guard isStudy, settings.pocketReminder, let next = Self.nextReminder(after: .now, minutes: settings.reminderMinutes) else { return }
        WKApplication.shared().scheduleBackgroundRefresh(withPreferredDate: next, userInfo: "pocketReminder" as NSString) { _ in }
    }

    static func nextReminder(after date: Date, minutes: [Int], calendar: Calendar = .current) -> Date? {
        let start = calendar.startOfDay(for: date)
        let candidates = [0, 1].flatMap { day in
            minutes.compactMap { m -> Date? in
                calendar.date(byAdding: .minute, value: m, to: calendar.date(byAdding: .day, value: day, to: start)!)
            }
        }
        return candidates.filter { $0 > date }.min()
    }

    func runPocketCheck(done: @escaping () -> Void) {
        defer { scheduleNextReminder() }
        guard isStudy, settings.pocketReminder else { done(); return }
        let session = WCSession.default
        let decide: (Int) -> Void = { steps in
            Task { @MainActor in
                let c = PilotController.shared
                let last = c.defaults.object(forKey: "pilotLastReminderMs") as? Int64
                let now = Self.nowMs()
                let cue = CueController.shared
                if Pilot.shouldRemindPocket(nowMs: now, stoodUp: true, phoneStepsLastMin: steps, phoneMovedLastMin: false,
                                            lastReminderMs: last, beatPlaying: cue.isRunning && cue.mode == "walking",
                                            enabled: c.settings.pocketReminder) {
                    c.defaults.set(now, forKey: "pilotLastReminderMs")
                    c.postPocketReminder()
                }
                done()
            }
        }
        guard session.activationState == .activated, session.isReachable else {
            // The iPhone is out of reach: it isn't coming along.
            decide(0); return
        }
        session.sendMessage([WatchKey.command: WatchKey.pocketQuery], replyHandler: { reply in
            decide(reply[WatchKey.steps] as? Int ?? 0)
        }, errorHandler: { _ in decide(0) })
    }

    private func postPocketReminder() {
        let content = UNMutableNotificationContent()
        content.title = L("pilot_pocket_title")
        content.body = L("pilot_pocket_body")
        content.sound = .default
        let request = UNNotificationRequest(identifier: "pocketReminder", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

/// The last 90 s of live acceleration during a study press (from the cue's motion updates), kept
/// in memory only. Filled on the motion queue, read on the main actor.
final class LiveAccRing: @unchecked Sendable {
    private let lock = NSLock()
    private var samples: [PressSamples.Sample] = []
    private static let keepMs = Int64(Pilot.bufferBeforeS + Pilot.bufferAfterS) * 1000

    func append(_ s: PressSamples.Sample) {
        lock.lock(); defer { lock.unlock() }
        samples.append(s)
        if let first = samples.first, s.tMs - first.tMs > Self.keepMs + 10_000 {
            samples.removeAll { s.tMs - $0.tMs > Self.keepMs }
        }
    }

    func snapshot() -> [PressSamples.Sample] { lock.lock(); defer { lock.unlock() }; return samples }
    func clear() { lock.lock(); samples.removeAll(); lock.unlock() }
}

/// Press files on the watch: Documents/pilot/pending (metadata and live motion waiting for the
/// 60 s after the press) and Documents/pilot (finished, until the iPhone has them).
enum PressStore {
    static var dir: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("pilot")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static var pendingDir: URL {
        let d = dir.appendingPathComponent("pending")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func savePending(_ record: PressRecord, live: [PressSamples.Sample]) {
        var r = record
        r.samples = PressSamples(tMs: live.map(\.tMs), ax: live.map(\.ax), ay: live.map(\.ay), az: live.map(\.az))
        try? r.json().write(to: pendingDir.appendingPathComponent(r.fileName))
    }

    static func pending() -> [(URL, PressRecord, [PressSamples.Sample])] {
        let files = (try? FileManager.default.contentsOfDirectory(at: pendingDir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }.compactMap { url in
            guard let data = try? Data(contentsOf: url), let r = PressRecord(json: data) else { return nil }
            let s = r.samples
            let live = s.tMs.indices.map { PressSamples.Sample(tMs: s.tMs[$0], ax: s.ax[$0], ay: s.ay[$0], az: s.az[$0]) }
            return (url, r, live)
        }
    }

    static func write(_ record: PressRecord) -> URL? {
        let url = dir.appendingPathComponent(record.fileName)
        do { try record.json().write(to: url); return url } catch { return nil }
    }

    static func finished() -> [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "json" }
    }
}
