//
//  DataModels.swift
//  DaedalusCompanion
//
//  Data models matching the daedalus_watch_kit Python library schema.
//  Shared between the watch BLE peripheral, chest strap BLE central, and session manager.
//

import Foundation

// MARK: - Event Labels (matching Python event_labels.py)

enum EventLabels: String, CaseIterable {
    // Session events
    case sessionStart = "SESSION_START"
    case deviceSetupDone = "DEVICE_SETUP_DONE"
    case syncTaps = "SYNC_TAPS"
    case syncClaps = "SYNC_CLAPS"
    case syncSitStand = "SYNC_SIT_STAND"
    case sessionEnd = "SESSION_END"
    
    // State events
    case restStart = "REST_START"
    case restEnd = "REST_END"
    case stressTaskStart = "STRESS_TASK_START"
    case stressTaskEnd = "STRESS_TASK_END"
    case pacedBreathingStart = "PACED_BREATHING_START"
    case pacedBreathingEnd = "PACED_BREATHING_END"
    
    // Motion events
    case motionStill = "MOTION_STILL"
    case motionWristFlex = "MOTION_WRIST_FLEX"
    case motionTremorLike = "MOTION_TREMOR_LIKE"
    case motionTapping = "MOTION_TAPPING"
    case motionWriting = "MOTION_WRITING"
    case motionScrolling = "MOTION_SCROLLING"
    case motionArmSwing = "MOTION_ARM_SWING"
    case motionWalkInPlace = "MOTION_WALK_IN_PLACE"
    case motionRubbing = "MOTION_RUBBING"
    case motionSpeechGesture = "MOTION_SPEECH_GESTURE"
    
    // Tremor events
    case tremorRestStart = "TREMOR_REST_START"
    case tremorRestEnd = "TREMOR_REST_END"
    case tremorPosturalStart = "TREMOR_POSTURAL_START"
    case tremorPosturalEnd = "TREMOR_POSTURAL_END"
    case tremorFtnStart = "TREMOR_FTN_START"
    case tremorFtnEnd = "TREMOR_FTN_END"
    case tremorTapStart = "TREMOR_TAP_START"
    case tremorTapEnd = "TREMOR_TAP_END"
    
    // Gait events
    case gaitNormalStart = "GAIT_NORMAL_START"
    case gaitNormalEnd = "GAIT_NORMAL_END"
    case gaitSlowStart = "GAIT_SLOW_START"
    case gaitSlowEnd = "GAIT_SLOW_END"
    case gaitFastStart = "GAIT_FAST_START"
    case gaitFastEnd = "GAIT_FAST_END"
    case gaitTurnStart = "GAIT_TURN_START"
    case gaitTurnEnd = "GAIT_TURN_END"
    case gaitDualTaskStart = "GAIT_DUAL_TASK_START"
    case gaitDualTaskEnd = "GAIT_DUAL_TASK_END"
    
    // Haptic events
    case hapticTrigger = "HAPTIC_TRIGGER"
    case hapticOnset = "HAPTIC_ONSET"
    case hapticOffset = "HAPTIC_OFFSET"
    case hapticLatencyTest = "HAPTIC_LATENCY_TEST"
    case hapticGaitCueStart = "HAPTIC_GAIT_CUE_START"
    case hapticGaitCueEnd = "HAPTIC_GAIT_CUE_END"
    case hapticBreathingStart = "HAPTIC_BREATHING_START"
    case hapticBreathingEnd = "HAPTIC_BREATHING_END"
    case hapticTremorCueStart = "HAPTIC_TREMOR_CUE_START"
    case hapticTremorCueEnd = "HAPTIC_TREMOR_CUE_END"
}

// MARK: - Data Models

/// Single IMU sample matching watch_imu.parquet schema
struct ImuSample {
    var epochMs: Int64
    var elapsedMs: Int64
    var ax: Float
    var ay: Float
    var az: Float
    var gx: Float
    var gy: Float
    var gz: Float
    var motionIntensity: Float
    
    /// CSV row matching Python schema
    var csvRow: String {
        "\(epochMs),\(elapsedMs),\(ax),\(ay),\(az),\(gx),\(gy),\(gz),\(motionIntensity)"
    }
    
    static let csvHeader = "epoch_ms,elapsed_ms,ax,ay,az,gx,gy,gz,motion_intensity"
}

/// Single HR sample matching watch_hr.parquet schema
struct HrSample {
    var epochMs: Int64
    var hrBpm: Float
    var hrvRmssdMs: Float?
    var hrQuality: Float?
    var source: String = "sensor"
    
    var csvRow: String {
        let rmssd = hrvRmssdMs.map { String($0) } ?? ""
        let quality = hrQuality.map { String($0) } ?? ""
        return "\(epochMs),\(hrBpm),\(rmssd),\(quality),\(source)"
    }
    
    static let csvHeader = "epoch_ms,hr_bpm,hrv_rmssd_ms,hr_quality,source"
}

/// RR interval sample from Cycplus H2 chest strap
struct EcgSample {
    var epochMs: Int64
    var rrMs: Float?
    var hrBpm: Float?
    var beatQuality: Float = 1.0
    
    var csvRow: String {
        // ecg_raw is always empty for Cycplus H2
        let rr = rrMs.map { String($0) } ?? ""
        let hr = hrBpm.map { String($0) } ?? ""
        return "\(epochMs),,\(rr),\(hr),\(beatQuality)"
    }
    
    static let csvHeader = "epoch_ms,ecg_raw,rr_ms,hr_bpm,beat_quality"
}

/// Event log entry matching phone_events.csv schema
struct EventEntry {
    var epochMs: Int64
    var eventName: String
    var source: String = "manual"
    var notes: String = ""
    
    var csvRow: String {
        "\(epochMs),\(eventName),\(source),\(notes)"
    }
    
    static let csvHeader = "epoch_ms,event_name,source,notes"
}

/// Session metadata matching session_metadata.json schema
struct SessionMetadata: Codable {
    var subjectId: String
    var sessionId: String
    var deviceModel: String = "Samsung Galaxy Watch5 Pro R920"
    var firmwareVersion: String = "unknown"
    var watchSide: String = "left"
    var phoneModel: String = "iPhone"
    var ecgDevice: String = "Cycplus H2"
    var sessionStartEpochMs: Int64 = 0
    var sessionEndEpochMs: Int64 = 0
    var protocolVersion: String = "v0.1"
    var cohort: String = "healthy_control"
    var clinicianPresent: Bool = false
    var notes: String = ""
    
    enum CodingKeys: String, CodingKey {
        case subjectId = "subject_id"
        case sessionId = "session_id"
        case deviceModel = "device_model"
        case firmwareVersion = "firmware_version"
        case watchSide = "watch_side"
        case phoneModel = "phone_model"
        case ecgDevice = "ecg_device"
        case sessionStartEpochMs = "session_start_epoch_ms"
        case sessionEndEpochMs = "session_end_epoch_ms"
        case protocolVersion = "protocol_version"
        case cohort
        case clinicianPresent = "clinician_present"
        case notes
    }
    
    func toJson() -> String? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted
        guard let data = try? encoder.encode(self) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
