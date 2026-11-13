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

    // Home pilot (study mode; nothing here is sent while the study switch is off)
    /// iPhone → watch (application context): `PilotSettings.dictionary`.
    static let pilot = "pilot"
    /// watch → iPhone (message with reply): are earbuds connected? Reply: `earbuds`.
    static let pilotQuery = "pilotQuery"
    /// watch → iPhone (message with reply): play the sound beat at `rate`. Reply: `ok`, and on
    /// success `beatStartMs` (first click, iPhone clock) with `r0`/`r1` (iPhone receive/reply times).
    static let beatStart = "beatStart"
    /// watch → iPhone: stop the sound beat.
    static let beatStop = "beatStop"
    /// iPhone → watch: the sound beat stopped on its own (earbuds removed); the wrist takes over.
    static let beatLost = "beatLost"
    /// watch → iPhone (message with reply): steps the iPhone counted in the last minute. Reply: `steps`.
    static let pocketQuery = "pocketQuery"
    /// watch → iPhone: pause or resume the sound beat (24).
    static let beatPause = "beatPause"
    static let beatResume = "beatResume"
    /// The press index a beat message is about (start, reply, stop, pause, resume, lost): a
    /// message for a press that isn't current is ignored (21).
    static let press = "press"
    static let earbuds = "earbuds"
    static let steps = "steps"
    static let ok = "ok"
    static let beatStartMs = "beatStartMs"
    static let r0 = "r0"
    static let r1 = "r1"
    /// File transfer metadata: "pilot" for press files (kept apart from session files, never backed up).
    static let fileKind = "kind"
    static let pilotFile = "pilot"
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
