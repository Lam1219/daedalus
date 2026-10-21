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
    func start(mode: CueMode) {
        let rate = LocalStore.shared.cueRate(mode)
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        if session.isReachable {
            session.sendMessage([WatchKey.command: WatchKey.start, WatchKey.mode: mode.id, WatchKey.rate: rate], replyHandler: nil)
        } else {
            var context = session.applicationContext
            context[WatchKey.pendingMode] = mode.id
            context[WatchKey.rate] = rate
            context[WatchKey.pendingAt] = Date.now
            try? session.updateApplicationContext(context)
            let config = HKWorkoutConfiguration()
            config.activityType = mode.id == "walking" ? .walking : .mindAndBody
            config.locationType = mode.id == "walking" ? .outdoor : .indoor
            health.startWatchApp(with: config) { _, error in
                if let error { print("Couldn't start the watch app: \(error)") }
            }
        }
        runningMode = mode.id
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
            self.sendRates()
        }
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.update(from: session); self.sendRates() }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in self.update(from: session) }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        Task { @MainActor in self.apply(status: message) }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        Task { @MainActor in self.apply(status: context) }
    }

    /// A finished session from the watch. The file must be moved before this method returns.
    nonisolated func session(_ session: WCSession, didReceive file: WCSessionFile) {
        let name = (file.metadata?[WatchKey.fileName] as? String) ?? file.fileURL.lastPathComponent
        let destination = LocalStore.sessionsDir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: destination)
        do {
            try FileManager.default.moveItem(at: file.fileURL, to: destination)
            Task { @MainActor in CloudBackup.shared.syncSoon() }
        } catch {
            print("Couldn't save session file \(name): \(error)")
        }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}
    nonisolated func sessionDidDeactivate(_ session: WCSession) { session.activate() }
}
