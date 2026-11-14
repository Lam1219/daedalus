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
        // 19: presses left waiting (also an orphan from a crash mid-beat) are written even if
        // study mode was turned off since.
        if PressStore.hasPending { sealDuePresses() }
        guard isStudy else { return }
        armRecorder()
        refreshEarbuds()
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
        // 8: only when the physio confirmed earbuds are paired (iOS can't tell them from a speaker).
        return Pilot.earbudsAvailable(paired: settings.earbudsPaired, headphoneRoute: earbuds.connected)
            && WCSession.default.isReachable
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
        beat = PressBeat(drawn: drawn, pressIndex: index)
        currentPlace = drawn
        armRecorder()
        if drawn != .wrist { startPhoneBeat(pace: pace, press: index) }
        // startPhoneBeat may already have fallen back (iPhone out of reach): then the wrist plays now.
        return beat?.wristPlays ?? true
    }

    /// 19: right after the beat started, the press is saved as a pending file, so a crash or a
    /// suspension mid-beat still leaves a press file (written later as an orphan).
    func pressStarted() {
        savePendingNow()
        beat?.pendingWasSaved()   // round 003, 9: from now on a newly set start needs its own save
    }

    private func savePendingNow() {
        guard var press else { return }
        if let beat {
            press.apply(beat)   // place, place_drawn, start, fallback (round 006)
        }
        PressStore.savePending(press, live: live.snapshot())
    }

    private func startPhoneBeat(pace: Int, press: Int64) {
        let t0 = Self.nowMs()
        let session = WCSession.default
        fallbackTask?.cancel()
        fallbackTask = Task { [weak self] in
            try? await Task.sleep(for: Self.phoneConfirmTimeout)
            guard !Task.isCancelled else { return }
            self?.fallBack(reason: "phone_no_confirm")
        }
        // The iPhone is out of reach: the wrist plays now, with no wait.
        guard session.activationState == .activated, session.isReachable else { fallBack(reason: "phone_unreachable"); return }
        let message: [String: Any] = [WatchKey.command: WatchKey.beatStart, WatchKey.rate: pace,
                                      WatchKey.press: NSNumber(value: press)]
        session.sendMessage(message, replyHandler: { reply in
            let t1 = Self.nowMs()
            Task { @MainActor in PilotController.shared.phoneReplied(reply, press: press, t0: t0, t1: t1) }
        }, errorHandler: { _ in
            Task { @MainActor in PilotController.shared.fallBack(forPress: press, reason: "phone_error") }
        })
    }

    /// 21: the reply names its press; one for a press that isn't current is ignored (and that
    /// press's beat on the iPhone is stopped).
    private func phoneReplied(_ reply: [String: Any], press sent: Int64, t0: Int64, t1: Int64) {
        let replyPress = (reply[WatchKey.press] as? NSNumber)?.int64Value ?? sent
        guard reply[WatchKey.ok] as? Bool == true,
              let start = (reply[WatchKey.beatStartMs] as? NSNumber)?.int64Value,
              let r0 = (reply[WatchKey.r0] as? NSNumber)?.int64Value,
              let r1 = (reply[WatchKey.r1] as? NSNumber)?.int64Value
        else { fallBack(forPress: replyPress, reason: "phone_refused"); return }
        let atMs = Pilot.beatStartOnWatchClock(t0: t0, t1: t1, r0: r0, r1: r1, beatStartPhoneMs: start)
        guard var beat, beat.phoneStarted(atMs: atMs, forPress: replyPress) else {
            // Too late, or another press: the wrist already plays. Silence that press on the iPhone.
            sendBeatStop(press: replyPress)
            return
        }
        fallbackTask?.cancel(); fallbackTask = nil
        self.beat = beat
        savePendingNow()
    }

    /// The iPhone couldn't start or keep the beat: the wrist plays at once. A press whose beat
    /// never started on the iPhone is recorded as a wrist press; one that lost its earbuds midway
    /// keeps the place where its beat started. `forPress`: the press an iPhone message is about
    /// (nil for the watch's own timeout); a message about another press is ignored (21).
    func fallBack(forPress press: Int64? = nil, reason: String) {
        guard var beat, beat.phoneFailed(forPress: press, reason: reason, atMs: Self.nowMs()) else { return }
        fallbackTask?.cancel(); fallbackTask = nil
        self.beat = beat
        currentPlace = beat.place
        sendBeatStop(press: beat.pressIndex)
        CueController.shared.fallBackToWrist()
    }

    /// 24: Pause and resume reach a beat the iPhone plays.
    func pauseChanged(paused: Bool) {
        guard let beat, beat.phonePlaying else { return }
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        let message: [String: Any] = [WatchKey.command: paused ? WatchKey.beatPause : WatchKey.beatResume,
                                      WatchKey.press: NSNumber(value: beat.pressIndex)]
        if session.isReachable { session.sendMessage(message, replyHandler: nil) } else { session.transferUserInfo(message) }
    }

    /// A wrist pulse of this press played (the first is the wrist's beat start).
    /// Round 002, 12, narrowed in round 003, 9: only the wrist's first pulse after a fallback saves
    /// the pending file (its start came after `pressStarted`'s save), so a crash right after keeps
    /// the start time. A direct wrist press does no second save: `pressStarted` already wrote its
    /// start. The save is queued behind the pulse itself, which is played right after this call.
    func wristBeatPlayed(atMs ms: Int64) {
        guard beat?.wristPulse(atMs: ms) == true else { return }
        Task { @MainActor [weak self] in self?.savePendingNow() }
    }

    /// The beat stopped (Stop, or the 10-minute limit): ask the check-in, or write the file.
    func endPress() {
        guard var press, var beat, !checkinPending else { return }
        fallbackTask?.cancel(); fallbackTask = nil
        let phoneWasAsked = beat.place != .wrist || beat.phonePlaying
        beat.stop()
        self.beat = nil
        press.apply(beat)   // place where the beat started, place_drawn, start, fallback (round 006)
        press.beatStopMs = Self.nowMs()
        self.press = press
        PressStore.savePending(press, live: live.snapshot())   // 19: the stop is on disk at once
        if phoneWasAsked { sendBeatStop(press: beat.pressIndex) }
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
    }

    /// 17: the stop goes now when the iPhone is reachable, and otherwise through queued delivery
    /// (`transferUserInfo`), which the iPhone gets when it can. It names its press (21), so a late
    /// stop never stops a newer beat.
    private func sendBeatStop(press: Int64) {
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        let message: [String: Any] = [WatchKey.command: WatchKey.beatStop, WatchKey.press: NSNumber(value: press)]
        if session.isReachable {
            session.sendMessage(message, replyHandler: nil, errorHandler: { _ in
                WCSession.default.transferUserInfo(message)
            })
        } else {
            session.transferUserInfo(message)
        }
    }

    @ObservationIgnored private var sealRetry: Task<Void, Never>?

    /// Writes each waiting press once the background recorder covers press + 60 s (18), or after
    /// `Pilot.sealGiveUpMs` with what there is, marked `motion_partial`. The running press is never
    /// written; an orphan (the app stopped mid-beat) is (19). Retries every 30 s while presses wait.
    ///
    /// Round 002, 10: the work (decoding the pending files, querying the recorder, writing) runs off
    /// the main thread, one pass at a time, and no pass runs while a press is running (the beat and
    /// its screen keep the main thread to themselves; the pass is retried afterwards).
    func sealDuePresses() {
        guard Pilot.sealRetryShouldRun(pressRunning: press != nil) else { scheduleSealRetry(); return }
        guard !sealing else { sealAgain = true; return }
        sealing = true
        Task { [weak self] in
            let (files, waiting) = await Task.detached(priority: .utility) { Self.sealPass(nowMs: Self.nowMs()) }.value
            guard let self else { return }
            for file in files { PhoneLink.shared.send(pilotFile: file) }
            self.sealing = false
            if self.sealAgain { self.sealAgain = false; self.sealDuePresses() } else if waiting { self.scheduleSealRetry() }
        }
    }

    @ObservationIgnored private var sealing = false
    @ObservationIgnored private var sealAgain = false

    private func scheduleSealRetry() {
        guard sealRetry == nil else { return }
        sealRetry = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            self?.sealRetry = nil
            self?.sealDuePresses()
        }
    }

    /// One pass over the waiting presses, off the main thread. Returns the files written and
    /// whether any press still waits. Only called when no press runs, so every pending file is
    /// finished or an orphan.
    private nonisolated static func sealPass(nowMs now: Int64) -> ([URL], Bool) {
        var written: [URL] = [], waiting = false
        for (url, record, liveSamples) in PressStore.pending() {
            let from = Date(timeIntervalSince1970: Double(record.pressMs - Int64(Pilot.bufferBeforeS) * 1000) / 1000)
            let to = Date(timeIntervalSince1970: Double(record.pressMs + Int64(Pilot.bufferAfterS) * 1000) / 1000)
            let recorded = now < record.pressMs + Int64(Pilot.bufferAfterS) * 1000 ? [] : recorded(from: from, to: to)
            switch Pilot.sealDecision(nowMs: now, pressMs: record.pressMs, recordedLastMs: recorded.map(\.tMs).max(),
                                      isCurrentPress: false) {
            case .wait:
                waiting = true
            case let .seal(partial):
                var full = record
                full.samples = PressSamples.merge(recorded: recorded, live: liveSamples, pressMs: record.pressMs)
                full.motionPartial = partial
                if let file = PressStore.write(full) {
                    try? FileManager.default.removeItem(at: url)
                    written.append(file)
                }
            }
        }
        return (written, waiting)
    }

    /// The background recorder's acceleration in [from, to], in m/s² with wall-clock ms.
    private nonisolated static func recorded(from: Date, to: Date) -> [PressSamples.Sample] {
        guard CMSensorRecorder.isAccelerometerRecordingAvailable(),
              let list = CMSensorRecorder().accelerometerData(from: from, to: to) else { return [] }
        var out: [PressSamples.Sample] = []
        for case let d as CMRecordedAccelerometerData in IteratorSequence(NSFastEnumerationIterator(list)) {
            let a = d.acceleration, g = 9.80665
            out.append(.init(tMs: Int64((d.startDate.timeIntervalSince1970 * 1000).rounded()), ax: a.x * g, ay: a.y * g, az: a.z * g))
        }
        return out
    }

    nonisolated static func nowMs() -> Int64 { Int64(Date.now.timeIntervalSince1970 * 1000) }

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
    /// Excluded from backup (20).
    static var dir: URL {
        PilotFiles.directory(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("pilot"))
    }

    static var pendingDir: URL { PilotFiles.directory(dir.appendingPathComponent("pending")) }

    /// The working copy of one press while it waits; each update replaces only that press's own
    /// pending file (its name carries the press index and press_ms).
    static func savePending(_ record: PressRecord, live: [PressSamples.Sample]) {
        var r = record
        r.samples = PressSamples(tMs: live.map(\.tMs), ax: live.map(\.ax), ay: live.map(\.ay), az: live.map(\.az))
        try? r.json().write(to: pendingDir.appendingPathComponent(r.fileName), options: .atomic)
    }

    /// Whether any press waits (names only: no decoding on the main thread).
    static var hasPending: Bool {
        ((try? FileManager.default.contentsOfDirectory(atPath: pendingDir.path)) ?? []).contains { $0.hasSuffix(".json") }
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

    /// A finished press file. Never replaces an existing one (15): a clash keeps both.
    static func write(_ record: PressRecord) -> URL? {
        guard let data = try? record.json() else { return nil }
        return try? PilotFiles.writeNew(data, in: dir, name: record.fileName)
    }

    static func finished() -> [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "json" }
    }
}
