package com.medicina.shared

/**
 * Event label constants matching daedalus_watch_kit Python library (event_labels.py).
 * Used by both the Wear OS watch app and Android companion app.
 */
object EventLabels {
    // Session events
    const val SESSION_START = "SESSION_START"
    const val DEVICE_SETUP_DONE = "DEVICE_SETUP_DONE"
    const val SYNC_TAPS = "SYNC_TAPS"
    const val SYNC_CLAPS = "SYNC_CLAPS"
    const val SYNC_SIT_STAND = "SYNC_SIT_STAND"
    const val SESSION_END = "SESSION_END"

    // State events
    const val REST_START = "REST_START"
    const val REST_END = "REST_END"
    const val STRESS_TASK_START = "STRESS_TASK_START"
    const val STRESS_TASK_END = "STRESS_TASK_END"
    const val STRESS_PRE_SCORE = "STRESS_PRE_SCORE"
    const val STRESS_POST_SCORE = "STRESS_POST_SCORE"
    const val PACED_BREATHING_START = "PACED_BREATHING_START"
    const val PACED_BREATHING_END = "PACED_BREATHING_END"

    // Motion events
    const val MOTION_STILL = "MOTION_STILL"
    const val MOTION_WRIST_FLEX = "MOTION_WRIST_FLEX"
    const val MOTION_TREMOR_LIKE = "MOTION_TREMOR_LIKE"
    const val MOTION_TAPPING = "MOTION_TAPPING"
    const val MOTION_WRITING = "MOTION_WRITING"
    const val MOTION_SCROLLING = "MOTION_SCROLLING"
    const val MOTION_ARM_SWING = "MOTION_ARM_SWING"
    const val MOTION_WALK_IN_PLACE = "MOTION_WALK_IN_PLACE"
    const val MOTION_RUBBING = "MOTION_RUBBING"
    const val MOTION_SPEECH_GESTURE = "MOTION_SPEECH_GESTURE"

    // Tremor events
    const val TREMOR_REST_START = "TREMOR_REST_START"
    const val TREMOR_REST_END = "TREMOR_REST_END"
    const val TREMOR_POSTURAL_START = "TREMOR_POSTURAL_START"
    const val TREMOR_POSTURAL_END = "TREMOR_POSTURAL_END"
    const val TREMOR_FTN_START = "TREMOR_FTN_START"
    const val TREMOR_FTN_END = "TREMOR_FTN_END"
    const val TREMOR_TAP_START = "TREMOR_TAP_START"
    const val TREMOR_TAP_END = "TREMOR_TAP_END"

    // Gait events
    const val GAIT_NORMAL_START = "GAIT_NORMAL_START"
    const val GAIT_NORMAL_END = "GAIT_NORMAL_END"
    const val GAIT_SLOW_START = "GAIT_SLOW_START"
    const val GAIT_SLOW_END = "GAIT_SLOW_END"
    const val GAIT_FAST_START = "GAIT_FAST_START"
    const val GAIT_FAST_END = "GAIT_FAST_END"
    const val GAIT_TURN_START = "GAIT_TURN_START"
    const val GAIT_TURN_END = "GAIT_TURN_END"
    const val GAIT_DUAL_TASK_START = "GAIT_DUAL_TASK_START"
    const val GAIT_DUAL_TASK_END = "GAIT_DUAL_TASK_END"

    // Haptic events
    const val HAPTIC_TRIGGER = "HAPTIC_TRIGGER"
    const val HAPTIC_ONSET = "HAPTIC_ONSET"
    const val HAPTIC_OFFSET = "HAPTIC_OFFSET"
    const val HAPTIC_LATENCY_TEST = "HAPTIC_LATENCY_TEST"
    const val HAPTIC_GAIT_CUE_START = "HAPTIC_GAIT_CUE_START"
    const val HAPTIC_GAIT_CUE_END = "HAPTIC_GAIT_CUE_END"
    const val HAPTIC_BREATHING_START = "HAPTIC_BREATHING_START"
    const val HAPTIC_BREATHING_END = "HAPTIC_BREATHING_END"
    const val HAPTIC_TREMOR_CUE_START = "HAPTIC_TREMOR_CUE_START"
    const val HAPTIC_TREMOR_CUE_END = "HAPTIC_TREMOR_CUE_END"

    // Automatic cues (Documentation/automatic-cues-design.md, 2.7). Written with source "auto"; their
    // notes hold key=value pairs separated by ';', for example
    //   type=tremor;confidence=0.73;start_ms=...;end_ms=...;action=prompted;model=tremor-stream-1.0;threshold_dps=6.0
    // Keys: type (tremor | fog | pocket), confidence (0-1), start_ms, end_ms, action (shadow | prompted |
    // accepted | declined | false | ignored | cued), model (detector VERSION), threshold_dps or
    // freeze_index_threshold, and for POCKET_STATE state (in_pocket | not_in_pocket | unreachable).
    const val AUTO_TREMOR_START = "AUTO_TREMOR_START"
    const val AUTO_TREMOR_END = "AUTO_TREMOR_END"
    const val AUTO_FOG_CANDIDATE = "AUTO_FOG_CANDIDATE"
    const val AUTO_CUE_PROMPT = "AUTO_CUE_PROMPT"
    const val AUTO_CUE_ACCEPTED = "AUTO_CUE_ACCEPTED"
    const val AUTO_CUE_DECLINED = "AUTO_CUE_DECLINED"
    const val AUTO_CUE_FALSE = "AUTO_CUE_FALSE"
    const val AUTO_CUE_IGNORED = "AUTO_CUE_IGNORED"
    const val AUTO_CUE_START = "AUTO_CUE_START"
    const val AUTO_CUE_END = "AUTO_CUE_END"
    const val GUARD_START = "GUARD_START"
    const val GUARD_END = "GUARD_END"
    const val GUARD_BATTERY = "GUARD_BATTERY"
    const val POCKET_STATE = "POCKET_STATE"
    const val POCKET_REMINDER = "POCKET_REMINDER"
    const val POCKET_REMINDER_DISMISSED = "POCKET_REMINDER_DISMISSED"

    /** All event labels as a set for validation */
    val ALL_EVENTS = setOf(
        SESSION_START, DEVICE_SETUP_DONE, SYNC_TAPS, SYNC_CLAPS, SYNC_SIT_STAND, SESSION_END,
        REST_START, REST_END, STRESS_TASK_START, STRESS_TASK_END, STRESS_PRE_SCORE, STRESS_POST_SCORE,
        PACED_BREATHING_START, PACED_BREATHING_END,
        MOTION_STILL, MOTION_WRIST_FLEX, MOTION_TREMOR_LIKE, MOTION_TAPPING, MOTION_WRITING,
        MOTION_SCROLLING, MOTION_ARM_SWING, MOTION_WALK_IN_PLACE, MOTION_RUBBING, MOTION_SPEECH_GESTURE,
        TREMOR_REST_START, TREMOR_REST_END, TREMOR_POSTURAL_START, TREMOR_POSTURAL_END,
        TREMOR_FTN_START, TREMOR_FTN_END, TREMOR_TAP_START, TREMOR_TAP_END,
        GAIT_NORMAL_START, GAIT_NORMAL_END, GAIT_SLOW_START, GAIT_SLOW_END,
        GAIT_FAST_START, GAIT_FAST_END, GAIT_TURN_START, GAIT_TURN_END,
        GAIT_DUAL_TASK_START, GAIT_DUAL_TASK_END,
        HAPTIC_TRIGGER, HAPTIC_ONSET, HAPTIC_OFFSET, HAPTIC_LATENCY_TEST,
        HAPTIC_GAIT_CUE_START, HAPTIC_GAIT_CUE_END, HAPTIC_BREATHING_START, HAPTIC_BREATHING_END,
        HAPTIC_TREMOR_CUE_START, HAPTIC_TREMOR_CUE_END,
        AUTO_TREMOR_START, AUTO_TREMOR_END, AUTO_FOG_CANDIDATE, AUTO_CUE_PROMPT, AUTO_CUE_ACCEPTED,
        AUTO_CUE_DECLINED, AUTO_CUE_FALSE, AUTO_CUE_IGNORED, AUTO_CUE_START, AUTO_CUE_END,
        GUARD_START, GUARD_END, GUARD_BATTERY, POCKET_STATE, POCKET_REMINDER, POCKET_REMINDER_DISMISSED,
    )
}
