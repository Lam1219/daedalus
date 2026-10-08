//
//  ProtocolBlocks.swift
//  DaedalusCompanion
//
//  Protocol block definitions from POC Section 7.
//  Each block has a start/end event, display name, duration, and description.
//

import Foundation

enum BlockCategory: String, CaseIterable {
    case setup = "Setup"
    case sync = "Sync"
    case rest = "Rest"
    case breathing = "Breath"
    case stress = "Stress"
    case movement = "Motion"
    case tremor = "Tremor"
    case gait = "Gait"
    case haptic = "Haptic"
    case recovery = "Recovery"
}

struct ProtocolBlock: Identifiable {
    let id: String
    let displayName: String
    let startEvent: String
    let endEvent: String
    let defaultDurationSec: Int
    let description: String
    let category: BlockCategory
    
    var targetDurationText: String {
        let minutes = defaultDurationSec / 60
        if minutes > 0 {
            return "Target: \(minutes) min"
        } else {
            return "Target: \(defaultDurationSec) sec"
        }
    }
}

enum ProtocolBlocks {
    static let all: [ProtocolBlock] = [
        // Setup
        ProtocolBlock(id: "block_0_setup", displayName: "Consent & Setup",
                      startEvent: EventLabels.deviceSetupDone.rawValue, endEvent: EventLabels.deviceSetupDone.rawValue,
                      defaultDurationSec: 900, description: "Participant ID, demographics, device placement, time sync",
                      category: .setup),
        
        // Sync
        ProtocolBlock(id: "block_sync_taps", displayName: "Sync: Triple Taps",
                      startEvent: EventLabels.syncTaps.rawValue, endEvent: EventLabels.syncTaps.rawValue,
                      defaultDurationSec: 30, description: "Tap watch against chest strap 3 times",
                      category: .sync),
        ProtocolBlock(id: "block_sync_claps", displayName: "Sync: Five Claps",
                      startEvent: EventLabels.syncClaps.rawValue, endEvent: EventLabels.syncClaps.rawValue,
                      defaultDurationSec: 30, description: "Clap 5 times while all devices record",
                      category: .sync),
        ProtocolBlock(id: "block_sync_sitstand", displayName: "Sync: Sit-to-Stand",
                      startEvent: EventLabels.syncSitStand.rawValue, endEvent: EventLabels.syncSitStand.rawValue,
                      defaultDurationSec: 60, description: "Perform 3 sit-to-stand movements",
                      category: .sync),
        
        // Rest
        ProtocolBlock(id: "block_2_rest", displayName: "Resting Baseline",
                      startEvent: EventLabels.restStart.rawValue, endEvent: EventLabels.restEnd.rawValue,
                      defaultDurationSec: 300, description: "Seated, arms supported, quiet, normal breathing (5 min)",
                      category: .rest),
        
        // Breathing
        ProtocolBlock(id: "block_3_breathing", displayName: "Paced Breathing",
                      startEvent: EventLabels.pacedBreathingStart.rawValue, endEvent: EventLabels.pacedBreathingEnd.rawValue,
                      defaultDurationSec: 180, description: "Inhale 4s, Exhale 6s (6 breaths/min) for 3 min",
                      category: .breathing),
        
        // Stress
        ProtocolBlock(id: "block_4_stress", displayName: "Stress Task",
                      startEvent: EventLabels.stressTaskStart.rawValue, endEvent: EventLabels.stressTaskEnd.rawValue,
                      defaultDurationSec: 420, description: "Mental arithmetic: count backwards from 2026 by 7 (5-7 min)",
                      category: .stress),
        
        // Movement
        ProtocolBlock(id: "block_5_motion_still", displayName: "Motion: Still Hand",
                      startEvent: EventLabels.motionStill.rawValue, endEvent: EventLabels.motionStill.rawValue,
                      defaultDurationSec: 60, description: "Still hand on lap (60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_wrist", displayName: "Motion: Wrist Flex/Ext",
                      startEvent: EventLabels.motionWristFlex.rawValue, endEvent: EventLabels.motionWristFlex.rawValue,
                      defaultDurationSec: 60, description: "Wrist flexion/extension (30-60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_tremor", displayName: "Motion: Tremor-like",
                      startEvent: EventLabels.motionTremorLike.rawValue, endEvent: EventLabels.motionTremorLike.rawValue,
                      defaultDurationSec: 60, description: "Hand shaking 3-6 Hz (30-60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_tapping", displayName: "Motion: Finger Tapping",
                      startEvent: EventLabels.motionTapping.rawValue, endEvent: EventLabels.motionTapping.rawValue,
                      defaultDurationSec: 60, description: "Finger tapping (30-60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_writing", displayName: "Motion: Writing",
                      startEvent: EventLabels.motionWriting.rawValue, endEvent: EventLabels.motionWriting.rawValue,
                      defaultDurationSec: 60, description: "Writing gesture (30-60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_scrolling", displayName: "Motion: Scrolling",
                      startEvent: EventLabels.motionScrolling.rawValue, endEvent: EventLabels.motionScrolling.rawValue,
                      defaultDurationSec: 60, description: "Phone scrolling (30-60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_armswing", displayName: "Motion: Arm Swing",
                      startEvent: EventLabels.motionArmSwing.rawValue, endEvent: EventLabels.motionArmSwing.rawValue,
                      defaultDurationSec: 60, description: "Arm swing while standing (30-60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_walkplace", displayName: "Motion: Walking in Place",
                      startEvent: EventLabels.motionWalkInPlace.rawValue, endEvent: EventLabels.motionWalkInPlace.rawValue,
                      defaultDurationSec: 60, description: "Walking in place (30-60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_rubbing", displayName: "Motion: Hand Rubbing",
                      startEvent: EventLabels.motionRubbing.rawValue, endEvent: EventLabels.motionRubbing.rawValue,
                      defaultDurationSec: 60, description: "Hand rubbing (30-60 sec)",
                      category: .movement),
        ProtocolBlock(id: "block_5_motion_speech", displayName: "Motion: Speech/Coughing",
                      startEvent: EventLabels.motionSpeechGesture.rawValue, endEvent: EventLabels.motionSpeechGesture.rawValue,
                      defaultDurationSec: 60, description: "Coughing/speaking movement (30-60 sec)",
                      category: .movement),
        
        // Tremor
        ProtocolBlock(id: "block_6_tremor_rest", displayName: "Tremor: Rest",
                      startEvent: EventLabels.tremorRestStart.rawValue, endEvent: EventLabels.tremorRestEnd.rawValue,
                      defaultDurationSec: 60, description: "Hands resting on thighs (60 sec)",
                      category: .tremor),
        ProtocolBlock(id: "block_6_tremor_postural", displayName: "Tremor: Postural",
                      startEvent: EventLabels.tremorPosturalStart.rawValue, endEvent: EventLabels.tremorPosturalEnd.rawValue,
                      defaultDurationSec: 60, description: "Arms extended forward (30-60 sec)",
                      category: .tremor),
        ProtocolBlock(id: "block_6_tremor_ftn", displayName: "Tremor: Finger-to-Nose",
                      startEvent: EventLabels.tremorFtnStart.rawValue, endEvent: EventLabels.tremorFtnEnd.rawValue,
                      defaultDurationSec: 60, description: "30 sec per side (60 sec total)",
                      category: .tremor),
        ProtocolBlock(id: "block_6_tremor_tap", displayName: "Tremor: Finger Tapping",
                      startEvent: EventLabels.tremorTapStart.rawValue, endEvent: EventLabels.tremorTapEnd.rawValue,
                      defaultDurationSec: 30, description: "Rapid finger tapping (30 sec)",
                      category: .tremor),
        
        // Gait
        ProtocolBlock(id: "block_7_gait_normal", displayName: "Gait: Normal Walking",
                      startEvent: EventLabels.gaitNormalStart.rawValue, endEvent: EventLabels.gaitNormalEnd.rawValue,
                      defaultDurationSec: 180, description: "Walk 10m back and forth for 2-3 min",
                      category: .gait),
        ProtocolBlock(id: "block_7_gait_slow", displayName: "Gait: Slow Walking",
                      startEvent: EventLabels.gaitSlowStart.rawValue, endEvent: EventLabels.gaitSlowEnd.rawValue,
                      defaultDurationSec: 60, description: "Slow walking (1 min)",
                      category: .gait),
        ProtocolBlock(id: "block_7_gait_fast", displayName: "Gait: Fast Walking",
                      startEvent: EventLabels.gaitFastStart.rawValue, endEvent: EventLabels.gaitFastEnd.rawValue,
                      defaultDurationSec: 60, description: "Fast walking (1 min, if safe)",
                      category: .gait),
        ProtocolBlock(id: "block_7_gait_turn", displayName: "Gait: Turning",
                      startEvent: EventLabels.gaitTurnStart.rawValue, endEvent: EventLabels.gaitTurnEnd.rawValue,
                      defaultDurationSec: 120, description: "180-degree turns, 10 turns (if safe)",
                      category: .gait),
        ProtocolBlock(id: "block_7_gait_dual", displayName: "Gait: Dual-Task",
                      startEvent: EventLabels.gaitDualTaskStart.rawValue, endEvent: EventLabels.gaitDualTaskEnd.rawValue,
                      defaultDurationSec: 120, description: "Walk while counting backwards or naming animals",
                      category: .gait),
        
        // Haptic
        ProtocolBlock(id: "block_8_haptic_latency", displayName: "Haptic: Latency Test",
                      startEvent: EventLabels.hapticLatencyTest.rawValue, endEvent: EventLabels.hapticLatencyTest.rawValue,
                      defaultDurationSec: 60, description: "Tap watch, measure vibration delay (target <150ms)",
                      category: .haptic),
        ProtocolBlock(id: "block_8_haptic_gait", displayName: "Haptic: Gait Cue",
                      startEvent: EventLabels.hapticGaitCueStart.rawValue, endEvent: EventLabels.hapticGaitCueEnd.rawValue,
                      defaultDurationSec: 180, description: "Haptic pulses at estimated cadence",
                      category: .haptic),
        ProtocolBlock(id: "block_8_haptic_breathing", displayName: "Haptic: Breathing Cue",
                      startEvent: EventLabels.hapticBreathingStart.rawValue, endEvent: EventLabels.hapticBreathingEnd.rawValue,
                      defaultDurationSec: 180, description: "Haptic pattern for 6 breaths/min",
                      category: .haptic),
        ProtocolBlock(id: "block_8_haptic_tremor", displayName: "Haptic: Tremor Cue",
                      startEvent: EventLabels.hapticTremorCueStart.rawValue, endEvent: EventLabels.hapticTremorCueEnd.rawValue,
                      defaultDurationSec: 60, description: "Low-frequency grounding pulses (2-3 Hz)",
                      category: .haptic),
    ]
    
    static func getByCategory() -> [BlockCategory: [ProtocolBlock]] {
        Dictionary(grouping: all) { $0.category }
    }
    
    static func getByCategory(_ category: BlockCategory) -> [ProtocolBlock] {
        all.filter { $0.category == category }
    }
}
