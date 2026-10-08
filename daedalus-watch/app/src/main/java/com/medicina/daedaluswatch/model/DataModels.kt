package com.medicina.daedaluswatch.model

/**
 * Shared data models matching daedalus_watch_kit Python library schema.
 */

data class SessionMetadata(
    val subjectId: String,
    val sessionId: String,
    val deviceModel: String = "Samsung Galaxy Watch5 Pro R920",
    val firmwareVersion: String = "unknown",
    val watchSide: String = "left",
    val phoneModel: String = "",
    val ecgDevice: String = "Cycplus H2",
    val sessionStartEpochMs: Long = 0L,
    val sessionEndEpochMs: Long = 0L,
    val protocolVersion: String = "v0.1",
    val cohort: String = "healthy_control",
    val clinicianPresent: Boolean = false,
    val notes: String = "",
)

data class ImuSample(
    val epochMs: Long,
    val elapsedMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val motionIntensity: Float = 0f,
)

data class HrSample(
    val epochMs: Long,
    val hrBpm: Float,
    val hrvRmssdMs: Float? = null,
    val hrQuality: Float? = null,
    val source: String = "sensor",
)

data class EventEntry(
    val epochMs: Long,
    val eventName: String,
    val source: String = "manual",
    val notes: String = "",
)

data class HapticEvent(
    val epochMs: Long,
    val eventType: String,
    val patternType: String = "",
)

object EventLabels {
    const val SESSION_START = "SESSION_START"
    const val SESSION_END = "SESSION_END"
    const val DEVICE_SETUP_DONE = "DEVICE_SETUP_DONE"
    const val SYNC_TAPS = "SYNC_TAPS"
    const val SYNC_CLAPS = "SYNC_CLAPS"
    const val SYNC_SIT_STAND = "SYNC_SIT_STAND"
    const val REST_START = "REST_START"
    const val REST_END = "REST_END"
    const val STRESS_TASK_START = "STRESS_TASK_START"
    const val STRESS_TASK_END = "STRESS_TASK_END"
    const val PACED_BREATHING_START = "PACED_BREATHING_START"
    const val PACED_BREATHING_END = "PACED_BREATHING_END"
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
    const val TREMOR_REST_START = "TREMOR_REST_START"
    const val TREMOR_REST_END = "TREMOR_REST_END"
    const val TREMOR_POSTURAL_START = "TREMOR_POSTURAL_START"
    const val TREMOR_POSTURAL_END = "TREMOR_POSTURAL_END"
    const val TREMOR_FTN_START = "TREMOR_FTN_START"
    const val TREMOR_FTN_END = "TREMOR_FTN_END"
    const val TREMOR_TAP_START = "TREMOR_TAP_START"
    const val TREMOR_TAP_END = "TREMOR_TAP_END"
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
}
