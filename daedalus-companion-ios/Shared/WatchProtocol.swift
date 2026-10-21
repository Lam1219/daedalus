import Foundation

/// Keys for the iPhone ↔ Apple Watch link (WatchConnectivity). Shared by both apps.
enum WatchKey {
    // iPhone → watch (application context: latest state, delivered even if the watch app is closed)
    /// [mode id: rate] — pulses per minute, or breaths per minute for "rest".
    static let rates = "rates"
    /// The cue to start when the iPhone launches the watch app (HKHealthStore.startWatchApp).
    static let pendingMode = "pendingMode"
    static let pendingAt = "pendingAt"

    // iPhone → watch (messages, while both apps are open)
    static let command = "cmd"
    static let start = "start"
    static let stop = "stop"
    static let mode = "mode"
    static let rate = "rate"

    // watch → iPhone
    /// The running cue mode, or "" when none.
    static let running = "running"
    static let heartRate = "hr"
    /// Session file transfers carry the file name in their metadata.
    static let fileName = "fileName"
}

/// Cue modes and their default rates (same ids as the Android apps and Supabase).
enum CueDefaults {
    static let rates: [String: Int] = ["walking": 80, "rest": 6, "writing": 90, "stress": 60]

    /// Session file name, the same as the Android watch: session_<mode>_<yyyyMMdd_HHmmss>.csv
    static func sessionFileName(mode: String, start: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return "session_\(mode)_\(f.string(from: start)).csv"
    }

    /// Same columns as the Android watch's session CSV, so analysis treats both alike.
    static let csvHeader = "timestamp_ms,heart_rate_bpm,ax,ay,az,gx,gy,gz,breathing_rate_brpm,spo2_percent\n"
}
