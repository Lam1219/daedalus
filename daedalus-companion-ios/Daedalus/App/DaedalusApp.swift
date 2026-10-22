import SwiftUI
import UserNotifications

@main
struct DaedalusApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .onOpenURL { url in Task { await AuthStore.shared.handle(url: url) } }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    private let notifications = NotificationDelegate()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = notifications
        MedReminders.registerCategory()
        Task { @MainActor in WatchLink.shared.activate() }
        // Every change to health records is backed up (no-op for guests or with backup off).
        LocalStore.shared.onChange = { Task { @MainActor in CloudBackup.shared.syncSoon() } }
        return true
    }
}

/// Signed out → welcome / sign-in; reset link → new password; first sign-in → setup; then the app.
struct RootView: View {
    @State private var auth = AuthStore.shared

    var body: some View {
        Group {
            switch auth.state {
            case .loading:
                DS.ground.ignoresSafeArea()
            case .signedOut:
                LoginFlow()
            case .settingNewPassword:
                NewPasswordView()
            case let .signedIn(email):
                ProfileSetupGate(email: email) { MainTabView() }
            case .guest:
                MainTabView()
            }
        }
        .tint(DS.sageDeep)
        .preferredColorScheme(.light)
        .task { await MedReminders.reschedule() }
    }
}
