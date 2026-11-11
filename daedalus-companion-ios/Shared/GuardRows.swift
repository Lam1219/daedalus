import Foundation

/// Guard sessions (automatic cues in shadow mode, Documentation/automatic-cues-design.md 2.2 and
/// 2.7): the session modes, and the rows a guard writes into its session CSV.
///
/// A guard records every motion sample and runs `GuardEngine` on it; nothing cues on its own yet.
/// Its CSV has the usual columns plus two trailing ones, `event` and `event_notes`:
/// - sample rows leave the two event cells empty;
/// - event rows fill only `timestamp_ms`, `event` and `event_notes` (the other nine cells blank).
/// The phone and daedalus_watch_kit read the columns by header name.
///
/// The Galaxy Watch writes the same rows (GuardSession.kt). Change both together.
enum GuardRows {
    static let walkGuard = "walk_guard"
    static let restGuard = "rest_guard"

    /// "I'm stuck" inside a guard: the same labels as EventLabels.kt.
    static let gaitCueStart = "HAPTIC_GAIT_CUE_START"
    static let gaitCueEnd = "HAPTIC_GAIT_CUE_END"

    /// A battery reading is logged at the start, every 5 minutes, and at the end.
    static let batteryInterval: Duration = .seconds(5 * 60)

    static let header = String(CueDefaults.csvHeader.dropLast()) + ",event,event_notes\n"
    /// Cells in a guard row.
    static let columns = 12

    static func isGuard(_ mode: String?) -> Bool { mode == walkGuard || mode == restGuard }

    /// The engine mode for a guard session mode.
    static func engineMode(_ mode: String) -> GuardEngine.Mode { mode == walkGuard ? .walk : .rest }

    /// One motion sample: heart rate (0 when unknown), acceleration in m/s² with gravity and rotation
    /// in rad/s with 4 decimals, as in every watch session CSV; breathing rate, SpO₂ and, in a guard,
    /// the event cells stay empty.
    static func sampleRow(tMs: Int64, heartRate: Int?, ax: Double, ay: Double, az: Double,
                          gx: Double, gy: Double, gz: Double, guardSession: Bool) -> String {
        String(
            format: guardSession ? "%lld,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,,,,\n" : "%lld,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,,\n",
            tMs, heartRate ?? 0, ax, ay, az, gx, gy, gz
        )
    }

    /// An event: time, nine blank cells, then the event name and its notes.
    static func eventRow(_ e: GuardEvent) -> String {
        "\(e.epochMs)" + String(repeating: ",", count: columns - 2) + "\(e.name),\(e.notes)\n"
    }

    /// `level` is WKInterfaceDevice.batteryLevel (0-1, or -1 when unknown, logged as -1 as on the
    /// Galaxy Watch). `charging` means on the charger, a full battery still plugged in included.
    static func battery(tMs: Int64, level: Float, charging: Bool) -> GuardEvent {
        let percent = level < 0 ? -1 : min(100, max(0, Int((Double(level) * 100).rounded())))
        return GuardEvent(epochMs: tMs, name: AutoCueEvents.guardBattery,
                          notes: "type=guard;battery_pct=\(percent);charging=\(charging)")
    }

    /// "I'm stuck" during a guard: the walking rhythm starts inside it (a labelled freeze for later evaluation).
    static func stuckCueStart(tMs: Int64, bpm: Int) -> GuardEvent {
        GuardEvent(epochMs: tMs, name: gaitCueStart, notes: "type=manual;trigger=im_stuck;bpm=\(bpm)")
    }

    static func stuckCueEnd(tMs: Int64) -> GuardEvent {
        GuardEvent(epochMs: tMs, name: gaitCueEnd, notes: "type=manual;trigger=stop")
    }
}
