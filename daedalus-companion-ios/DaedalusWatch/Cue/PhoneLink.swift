import Foundation
import Observation
import WatchConnectivity

/// The watch's side of the iPhone link: receives cue rates and start/stop, reports what's
/// running, and sends finished session files (kept until the transfer completes).
@MainActor
@Observable
final class PhoneLink: NSObject {
    static let shared = PhoneLink()
    private(set) var phoneReachable = false

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Tells the iPhone which cue is running (or none) and the latest heart rate.
    func sendStatus() {
        let cue = CueController.shared
        let status: [String: Any] = [WatchKey.running: cue.mode ?? "", WatchKey.heartRate: cue.heartRate ?? 0]
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        try? session.updateApplicationContext(status)
        if session.isReachable { session.sendMessage(status, replyHandler: nil) }
    }

    func send(file: URL) {
        guard WCSession.default.activationState == .activated else { return }
        WCSession.default.transferFile(file, metadata: [WatchKey.fileName: file.lastPathComponent])
    }

    /// Re-sends finished files that aren't on their way yet (e.g. recorded before activation).
    private func resendPending() {
        let session = WCSession.default
        let inFlight = Set(session.outstandingFileTransfers.map { $0.file.fileURL.lastPathComponent })
        let files = (try? FileManager.default.contentsOfDirectory(at: SessionRecorder.sessionsDir, includingPropertiesForKeys: nil)) ?? []
        let recording = CueController.shared.isRunning ? files.max { $0.lastPathComponent < $1.lastPathComponent }?.lastPathComponent : nil
        for file in files where file.pathExtension == "csv" && !inFlight.contains(file.lastPathComponent) && file.lastPathComponent != recording {
            send(file: file)
        }
    }

    private func apply(context: [String: Any]) {
        if let rates = context[WatchKey.rates] as? [String: Int] {
            UserDefaults.standard.set(rates, forKey: WatchKey.rates)
        }
    }

    private func handle(message: [String: Any]) {
        switch message[WatchKey.command] as? String {
        case WatchKey.start:
            if let mode = message[WatchKey.mode] as? String {
                CueController.shared.start(mode: mode, rate: message[WatchKey.rate] as? Int)
            }
        case WatchKey.stop:
            CueController.shared.stop()
        default:
            apply(context: message)
        }
    }

    /// The cue the iPhone asked for when it launched this app (HKHealthStore.startWatchApp).
    func takePendingMode() -> (mode: String, rate: Int?)? {
        let context = WCSession.default.receivedApplicationContext
        guard let mode = context[WatchKey.pendingMode] as? String, !mode.isEmpty,
              let at = context[WatchKey.pendingAt] as? Date, Date.now.timeIntervalSince(at) < 60,
              UserDefaults.standard.object(forKey: "lastPendingAt") as? Date != at
        else { return nil }
        UserDefaults.standard.set(at, forKey: "lastPendingAt")
        return (mode, context[WatchKey.rate] as? Int)
    }
}

extension PhoneLink: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        let context = session.receivedApplicationContext
        let reachable = session.isReachable
        Task { @MainActor in
            self.phoneReachable = reachable
            self.apply(context: context)
            self.resendPending()
            self.sendStatus()
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        Task { @MainActor in self.phoneReachable = reachable }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        Task { @MainActor in self.apply(context: context) }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        Task { @MainActor in self.handle(message: message) }
    }

    nonisolated func session(_ session: WCSession, didFinish fileTransfer: WCSessionFileTransfer, error: Error?) {
        // Delivered: the iPhone has it now (and backs it up), so free the watch's storage.
        guard error == nil else { return }
        try? FileManager.default.removeItem(at: fileTransfer.file.fileURL)
    }
}
