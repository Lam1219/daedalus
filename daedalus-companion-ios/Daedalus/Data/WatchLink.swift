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
    /// The cue running on the watch, if any (only ever set from what the watch reports).
    private(set) var runningMode: String?
    private(set) var heartRate: Int?
    /// A start was sent and the watch hasn't confirmed it yet ("Starting…").
    private(set) var startingMode: String?
    /// A stop was sent and the watch hasn't confirmed it yet ("Stopping…").
    private(set) var stopping = false
    /// The watch didn't confirm the last start or stop in time.
    private(set) var noReply = false

    @ObservationIgnored private var pendingRate = 0
    @ObservationIgnored private var replyTimeout: Task<Void, Never>?

    @ObservationIgnored private let health = HKHealthStore()

    var isReady: Bool { isPaired && isAppInstalled }

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
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
    /// The screen shows "Starting…" until the watch reports the cue running.
    func start(mode: CueMode) {
        let rate = LocalStore.shared.cueRate(mode)
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        startingMode = mode.id
        pendingRate = rate
        noReply = false
        if session.isReachable {
            sendStart()
        } else {
            var context = session.applicationContext
            context[WatchKey.pendingMode] = mode.id
            context[WatchKey.rate] = rate
            context[WatchKey.pendingAt] = Date.now
            try? session.updateApplicationContext(context)
            let config = HKWorkoutConfiguration()
            config.activityType = CueDefaults.launchActivity[mode.id] ?? .walking
            config.locationType = mode.id == "walking" ? .outdoor : .indoor
            health.startWatchApp(with: config) { _, error in
                if let error { print("Couldn't start the watch app: \(error)") }
            }
        }
        waitForReply()
    }

    func stop() {
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        stopping = true
        noReply = false
        session.sendMessage([WatchKey.command: WatchKey.stop], replyHandler: nil) { error in print("Stop not sent: \(error)") }
        // Clear any start still waiting in the application context, so a late launch can't restart it.
        var context = session.applicationContext
        context[WatchKey.pendingMode] = ""
        try? session.updateApplicationContext(context)
        waitForReply()
    }

    private func sendStart() {
        guard let mode = startingMode else { return }
        WCSession.default.sendMessage([WatchKey.command: WatchKey.start, WatchKey.mode: mode, WatchKey.rate: pendingRate], replyHandler: nil) { error in
            print("Start not sent: \(error)")
        }
    }

    /// Gives up on "Starting…" / "Stopping…" if the watch doesn't answer in 15 seconds.
    private func waitForReply() {
        replyTimeout?.cancel()
        replyTimeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(15))
            guard let self, !Task.isCancelled, self.startingMode != nil || self.stopping else { return }
            self.startingMode = nil
            self.stopping = false
            self.noReply = true
        }
    }

    private func update(from session: WCSession) {
        isPaired = session.isPaired
        isAppInstalled = session.isWatchAppInstalled
        isReachable = session.isReachable
    }

    private func apply(status: [String: Any]) {
        if let running = status[WatchKey.running] as? String {
            runningMode = running.isEmpty ? nil : running
            // The watch confirmed the start or stop.
            if let starting = startingMode, runningMode == starting { startingMode = nil; replyTimeout?.cancel() }
            if stopping, runningMode == nil { stopping = false; replyTimeout?.cancel() }
        }
        if let hr = status[WatchKey.heartRate] as? Int { heartRate = hr > 0 ? hr : nil }
    }
}

extension WatchLink: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        let status = session.receivedApplicationContext
        Task { @MainActor in
            self.update(from: session)
            self.apply(status: status)
            self.sendRates()
        }
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.update(from: session); self.sendRates() }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in
            self.update(from: session)
            // The launched watch app is open now: send the start directly too (the watch ignores a repeat).
            if session.isReachable, self.startingMode != nil { self.sendStart() }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        Task { @MainActor in self.apply(status: message) }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        Task { @MainActor in self.apply(status: context) }
    }

    /// A finished session from the watch. The file must be moved before this method returns.
    nonisolated func session(_ session: WCSession, didReceive file: WCSessionFile) {
        // Only a plain file name, so nothing can be written outside the sessions folder.
        let name = URL(fileURLWithPath: (file.metadata?[WatchKey.fileName] as? String) ?? file.fileURL.lastPathComponent).lastPathComponent
        let destination = LocalStore.sessionsDir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: destination)
        do {
            try FileManager.default.moveItem(at: file.fileURL, to: destination)
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: destination.path
            )
            Task { @MainActor in CloudBackup.shared.syncSoon() }
        } catch {
            print("Couldn't save session file \(name): \(error)")
        }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}
    nonisolated func sessionDidDeactivate(_ session: WCSession) { session.activate() }
}
