package com.medicina.daedaluscompanion.model

/**
 * Session metadata matching daedalus_watch_kit Python library (schemas.py).
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

/** Single IMU sample matching watch_imu.parquet schema */
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

/** Single HR/HRV sample matching watch_hr.parquet schema */
data class HrSample(
    val epochMs: Long,
    val hrBpm: Float,
    val hrvRmssdMs: Float? = null,
    val hrQuality: Float? = null,
    val source: String = "sensor",
)

/** Single RR interval sample from Cycplus H2 chest strap */
data class EcgSample(
    val epochMs: Long,
    val rrMs: Float? = null,
    val hrBpm: Float? = null,
    val beatQuality: Float = 1f,
) {
    val ecgRaw: Float = Float.NaN
}

/** Single event label entry matching phone_events.csv schema */
data class EventEntry(
    val epochMs: Long,
    val eventName: String,
    val source: String = "manual",
    val notes: String = "",
)

/** Haptic event log entry */
data class HapticEvent(
    val epochMs: Long,
    val eventType: String,
    val patternType: String = "",
)
