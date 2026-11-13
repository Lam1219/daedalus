import CoreMotion
import Foundation
import Observation

/// Home pilot settings on the iPhone (Profile → Home pilot), sent to the watch, and the press
/// files that come back from it. Press files stay on this phone (Documents/pilot, never backed
/// up) until someone exports them.
@MainActor
@Observable
final class PilotStore {
    static let shared = PilotStore()

    var settings: PilotSettings {
        didSet {
            guard settings != oldValue else { return }
            UserDefaults.standard.set(settings.dictionary, forKey: WatchKey.pilot)
            WatchLink.shared.sendPilot()
        }
    }
    /// Counts press files received, so the pilot screen can reload them.
    private(set) var filesReceived = 0

    private init() {
        settings = PilotSettings(dictionary: UserDefaults.standard.dictionary(forKey: WatchKey.pilot) ?? [:])
    }

    func fileArrived() { filesReceived += 1 }

    nonisolated static var dir: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("pilot")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    nonisolated static func files() -> [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    /// Part of "Delete everything on this phone".
    nonisolated static func deleteFiles() {
        for f in files() { try? FileManager.default.removeItem(at: f) }
    }

    // MARK: Phone carried (B10)

    @ObservationIgnored private let pedometer = CMPedometer()

    /// Steps this iPhone counted in the last minute (0 when unknown or not allowed).
    func stepsLastMinute() async -> Int {
        guard CMPedometer.isStepCountingAvailable() else { return 0 }
        let now = Date.now
        return await withCheckedContinuation { cont in
            pedometer.queryPedometerData(from: now.addingTimeInterval(-Double(Pilot.phoneCarriedWindowS)), to: now) { data, _ in
                cont.resume(returning: data?.numberOfSteps.intValue ?? 0)
            }
        }
    }
}
