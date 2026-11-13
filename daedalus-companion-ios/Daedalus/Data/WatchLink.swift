import AVFoundation
import Foundation
import HealthKit
import Observation
import WatchConnectivity

/// The iPhone's side of the Apple Watch link: watch status, cue rates, start/stop, and
/// session files arriving from the watch (saved with the other sessions and backed up).
@MainActor
@Observable
final class WatchLink: NSObject {
    static let shared = WatchLink()

    private(set) var isPaired = false
    private(set) var isAppInstalled = false
    private(set) var isReachable = false
    /// The cue running on the watch, if any.
    private(set) var runningMode: String?
    private(set) var heartRate: Int?
    /// Counts session files received, so screens can reload them.
    private(set) var filesReceived = 0

    @ObservationIgnored private let health = HKHealthStore()

    var isReady: Bool { isPaired && isAppInstalled }

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
        // Home pilot: tell the watch when earbuds come and go (study mode only), and hand the
        // beat back to the wrist when they go mid-beat.
        PhoneBeatPlayer.shared.onLost = { [weak self] press in self?.sendBeatLost(press: press) }
        NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { WatchLink.shared.sendEarbuds() }
        }
    }

    // MARK: Home pilot

    /// Sends the pilot settings (study switch, participant, seed, enrolment day, reminder times).
    func sendPilot() {
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired else { return }
        var context = session.applicationContext
        context[WatchKey.pilot] = PilotStore.shared.settings.dictionary
        if PilotStore.shared.settings.isActive { context[WatchKey.earbuds] = Self.pilotEarbuds }
        try? session.updateApplicationContext(context)
    }

    /// 8: earbuds count only with a headphone route and the physio's "Earbuds paired: yes".
    private static var pilotEarbuds: Bool {
        Pilot.earbudsAvailable(paired: PilotStore.shared.settings.earbudsPaired, headphoneRoute: PhoneBeatPlayer.earbudsConnected)
    }

    private func sendEarbuds() {
        guard PilotStore.shared.settings.isActive else { return }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired else { return }
        let connected = Self.pilotEarbuds
        var context = session.applicationContext
        context[WatchKey.earbuds] = connected
        try? session.updateApplicationContext(context)
        if session.isReachable { session.sendMessage([WatchKey.earbuds: connected], replyHandler: nil) }
    }

    private func sendBeatLost(press: Int64) {
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return }
        session.sendMessage([WatchKey.command: WatchKey.beatLost, WatchKey.press: NSNumber(value: press)], replyHandler: nil)
    }

    /// Beat commands without a reply (stop, pause, resume), by message or queued delivery (17, 24).
    /// Each names its press, so a late one never reaches a newer beat (21).
    private func handleBeatCommand(_ message: [String: Any]) -> Bool {
        guard let press = (message[WatchKey.press] as? NSNumber)?.int64Value else {
            // A stop without a press (an older watch build) still stops: never leave a beat running.
            if message[WatchKey.command] as? String == WatchKey.beatStop { PhoneBeatPlayer.shared.stop(); return true }
            return false
        }
        switch message[WatchKey.command] as? String {
        case WatchKey.beatStop: PhoneBeatPlayer.shared.stop(press: press)
        case WatchKey.beatPause: PhoneBeatPlayer.shared.setPaused(true, press: press)
        case WatchKey.beatResume: PhoneBeatPlayer.shared.setPaused(false, press: press)
        default: return false
        }
        return true
    }

    /// Messages from the watch that need an answer (study mode only).
    private func reply(to message: [String: Any], receivedMs r0: Int64) async -> [String: Any] {
        let pilot = PilotStore.shared.settings
        switch message[WatchKey.command] as? String {
        case WatchKey.pilotQuery:
            return [WatchKey.earbuds: pilot.isActive && Self.pilotEarbuds]
        case WatchKey.beatStart:
            let press = (message[WatchKey.press] as? NSNumber)?.int64Value ?? -1
            guard pilot.isActive, pilot.earbudsPaired, let pace = message[WatchKey.rate] as? Int,
                  let start = await PhoneBeatPlayer.shared.start(pace: pace, press: press)
            else { return [WatchKey.ok: false, WatchKey.press: NSNumber(value: press)] }
            return [WatchKey.ok: true, WatchKey.press: NSNumber(value: press),
                    WatchKey.beatStartMs: NSNumber(value: start), WatchKey.r0: NSNumber(value: r0),
                    WatchKey.r1: NSNumber(value: Int64(Date.now.timeIntervalSince1970 * 1000))]
        case WatchKey.pocketQuery:
            return [WatchKey.steps: pilot.isActive ? await PilotStore.shared.stepsLastMinute() : 0]
        default:
            return [:]
        }
    }

    /// Sends the rates chosen on the Cues screen; the watch keeps them for "I'm stuck".
    func sendRates() {
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired else { return }
        var context = session.applicationContext
        context[WatchKey.rates] = Dictionary(uniqueKeysWithValues: CueMode.all.map { ($0.id, LocalStore.shared.cueRate($0)) })
        try? session.updateApplicationContext(context)
    }

    /// Starts a cue on the watch: directly if the watch app is open, otherwise by launching it.
    func start(mode: CueMode) {
        start(modeId: mode.id, rate: LocalStore.shared.cueRate(mode))
    }

    /// Starts the tremor check on the watch (the rate is ignored there).
    func startTremorCheck() {
        start(modeId: TremorAnalyzer.mode, rate: 0)
    }

    private func start(modeId: String, rate: Int) {
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        if session.isReachable {
            session.sendMessage([WatchKey.command: WatchKey.start, WatchKey.mode: modeId, WatchKey.rate: rate], replyHandler: nil)
        } else {
            var context = session.applicationContext
            context[WatchKey.pendingMode] = modeId
            context[WatchKey.rate] = rate
            context[WatchKey.pendingAt] = Date.now
            try? session.updateApplicationContext(context)
            let config = HKWorkoutConfiguration()
            config.activityType = modeId == "walking" ? .walking : .mindAndBody
            config.locationType = modeId == "walking" ? .outdoor : .indoor
            health.startWatchApp(with: config) { _, error in
                if let error { print("Couldn't start the watch app: \(error)") }
            }
        }
        runningMode = modeId
    }

    func stop() {
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        session.sendMessage([WatchKey.command: WatchKey.stop], replyHandler: nil)
        runningMode = nil
    }

    private func update(from session: WCSession) {
        isPaired = session.isPaired
        isAppInstalled = session.isWatchAppInstalled
        isReachable = session.isReachable
    }

    private func apply(status: [String: Any]) {
        if let running = status[WatchKey.running] as? String { runningMode = running.isEmpty ? nil : running }
        if let hr = status[WatchKey.heartRate] as? Int { heartRate = hr > 0 ? hr : nil }
    }
}

extension WatchLink: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        let status = session.receivedApplicationContext
        Task { @MainActor in
            self.update(from: session)
            self.apply(status: status)
            self.sendRates(); self.sendPilot()
        }
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.update(from: session); self.sendRates(); self.sendPilot() }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in self.update(from: session) }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        nonisolated(unsafe) let message = message
        Task { @MainActor in
            if !self.handleBeatCommand(message) { self.apply(status: message) }
        }
    }

    /// 17, 24: beat commands queued by the watch while this iPhone wasn't reachable.
    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any]) {
        nonisolated(unsafe) let userInfo = userInfo
        Task { @MainActor in _ = self.handleBeatCommand(userInfo) }
    }

    /// Home pilot requests from the watch (earbuds state, start the sound beat, recent steps).
    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                             replyHandler: @escaping ([String: Any]) -> Void) {
        let r0 = Int64(Date.now.timeIntervalSince1970 * 1000)
        nonisolated(unsafe) let message = message
        Task { @MainActor in replyHandler(await self.reply(to: message, receivedMs: r0)) }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        Task { @MainActor in self.apply(status: context) }
    }

    /// A finished session from the watch. The file must be moved before this method returns.
    nonisolated func session(_ session: WCSession, didReceive file: WCSessionFile) {
        // Only a plain file name, so nothing can be written outside the sessions folder.
        let name = URL(fileURLWithPath: (file.metadata?[WatchKey.fileName] as? String) ?? file.fileURL.lastPathComponent).lastPathComponent
        // Home pilot press files stay on this phone in their own folder: never backed up.
        let pilot = file.metadata?[WatchKey.fileKind] as? String == WatchKey.pilotFile
        do {
            let destination: URL
            if pilot {
                // 15: a pilot file never replaces another; a clash keeps both.
                destination = try PilotFiles.moveNew(file.fileURL, into: PilotStore.dir, name: name)
            } else {
                destination = LocalStore.sessionsDir.appendingPathComponent(name)
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: file.fileURL, to: destination)
            }
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: destination.path
            )
            Task { @MainActor in
                if pilot { PilotStore.shared.fileArrived(); return }
                self.filesReceived += 1
                CloudBackup.shared.syncSoon()
            }
        } catch {
            print("Couldn't save session file \(name): \(error)")
        }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}
    nonisolated func sessionDidDeactivate(_ session: WCSession) { session.activate() }
}
