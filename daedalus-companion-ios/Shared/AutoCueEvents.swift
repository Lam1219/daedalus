import Foundation

/// Event labels for automatic cues, the same strings as EventLabels.kt and event_labels.py.
/// Automatic cues (Documentation/automatic-cues-design.md, 2.7). Written with source "auto"; their
/// notes hold key=value pairs separated by ';', for example
///   type=tremor;confidence=0.73;start_ms=...;end_ms=...;action=prompted;model=tremor-stream-1.0;threshold_dps=6.0
/// Keys: type (tremor | fog | pocket), confidence (0-1), start_ms, end_ms, action (shadow | prompted |
/// accepted | declined | false | ignored | cued), model (detector VERSION), threshold_dps or
/// freeze_index_threshold, and for POCKET_STATE state (in_pocket | not_in_pocket | unreachable).
/// Change all copies together.
enum AutoCueEvents {
    static let autoTremorStart = "AUTO_TREMOR_START"
    static let autoTremorEnd = "AUTO_TREMOR_END"
    static let autoFogCandidate = "AUTO_FOG_CANDIDATE"
    static let autoCuePrompt = "AUTO_CUE_PROMPT"
    static let autoCueAccepted = "AUTO_CUE_ACCEPTED"
    static let autoCueDeclined = "AUTO_CUE_DECLINED"
    static let autoCueFalse = "AUTO_CUE_FALSE"
    static let autoCueIgnored = "AUTO_CUE_IGNORED"
    static let autoCueStart = "AUTO_CUE_START"
    static let autoCueEnd = "AUTO_CUE_END"
    static let guardStart = "GUARD_START"
    static let guardEnd = "GUARD_END"
    static let pocketState = "POCKET_STATE"
    static let pocketReminder = "POCKET_REMINDER"
    static let pocketReminderDismissed = "POCKET_REMINDER_DISMISSED"

    static let all: [String] = [
        "AUTO_TREMOR_START",
        "AUTO_TREMOR_END",
        "AUTO_FOG_CANDIDATE",
        "AUTO_CUE_PROMPT",
        "AUTO_CUE_ACCEPTED",
        "AUTO_CUE_DECLINED",
        "AUTO_CUE_FALSE",
        "AUTO_CUE_IGNORED",
        "AUTO_CUE_START",
        "AUTO_CUE_END",
        "GUARD_START",
        "GUARD_END",
        "POCKET_STATE",
        "POCKET_REMINDER",
        "POCKET_REMINDER_DISMISSED",
    ]
}
