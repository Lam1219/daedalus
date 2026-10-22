import HealthKit
import SwiftUI
import WatchKit

@main
struct DaedalusWatchApp: App {
    @WKApplicationDelegateAdaptor(WatchAppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup { WatchRootView() }
    }
}

final class WatchAppDelegate: NSObject, WKApplicationDelegate {
    func applicationDidFinishLaunching() {
        Task { @MainActor in PhoneLink.shared.activate() }
    }

    /// The iPhone's "Start cue on watch" launches this app with a workout configuration;
    /// the cue to start was sent just before in the application context. That can arrive a
    /// moment after the launch, so wait up to 3 seconds for it, then fall back to the mode the
    /// workout type stands for.
    func handle(_ workoutConfiguration: HKWorkoutConfiguration) {
        Task { @MainActor in
            var pending = PhoneLink.shared.takePendingMode()
            for _ in 0..<12 where pending == nil {
                try? await Task.sleep(for: .milliseconds(250))
                pending = PhoneLink.shared.takePendingMode()
            }
            let mode = pending?.mode ?? CueDefaults.mode(launchedWith: workoutConfiguration.activityType)
            CueController.shared.start(mode: mode, rate: pending?.rate)
        }
    }
}

/// Watch palette (dark, from the design): sage on near-black.
enum WS {
    static let ground = Color(red: 0x12 / 255, green: 0x16 / 255, blue: 0x12 / 255)
    static let surface = Color(red: 0x25 / 255, green: 0x2D / 255, blue: 0x25 / 255)
    static let raised = Color(red: 0x2A / 255, green: 0x33 / 255, blue: 0x2A / 255)
    static let sage = Color(red: 0xA8 / 255, green: 0xC4 / 255, blue: 0x9E / 255)
    static let ink = Color(red: 0xF1 / 255, green: 0xF4 / 255, blue: 0xEF / 255)
    static let muted = Color(red: 0xA3 / 255, green: 0xAF / 255, blue: 0xA3 / 255)
    static let soft = Color(red: 0xC8 / 255, green: 0xD3 / 255, blue: 0xC5 / 255)
}

func L(_ key: String) -> String { NSLocalizedString(key, comment: "") }
func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), locale: .current, arguments: args)
}

struct WatchRootView: View {
    @State private var cue = CueController.shared

    var body: some View {
        Group {
            switch cue.mode {
            case "walking": WalkView()
            case "rest": BreatheView()
            case let mode?: PulseView(mode: mode)
            case nil: HomeView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(WS.ground.ignoresSafeArea())
        .task { await cue.requestAuthorization() }
        .onChange(of: cue.heartRate) { _, _ in PhoneLink.shared.sendStatus() }
    }
}
