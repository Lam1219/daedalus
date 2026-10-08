package com.medicina.daedaluscompanion.session

import com.medicina.daedaluscompanion.model.EventLabels

/**
 * Protocol block definitions from POC Section 7.
 * Each block has a start/end event, display name, duration, and description.
 */
data class ProtocolBlock(
    val id: String,
    val displayName: String,
    val startEvent: String,
    val endEvent: String,
    val defaultDurationSec: Int,
    val description: String,
    val category: BlockCategory,
) {
    val targetDurationText: String
        get() {
            val minutes = defaultDurationSec / 60
            return if (minutes > 0) "Target: $minutes min" else "Target: $defaultDurationSec sec"
        }
}

enum class BlockCategory {
    SETUP, REST, BREATHING, STRESS, MOVEMENT, TREMOR, GAIT, HAPTIC, RECOVERY, SYNC
}

object ProtocolBlocks {
    val ALL = listOf(
        // Block 0: Consent and setup
        ProtocolBlock(
            id = "block_0_setup",
            displayName = "Consent & Setup",
            startEvent = EventLabels.DEVICE_SETUP_DONE,
            endEvent = EventLabels.DEVICE_SETUP_DONE,
            defaultDurationSec = 900,
            description = "Participant ID, demographics, device placement, time sync",
            category = BlockCategory.SETUP,
        ),

        // Sync events
        ProtocolBlock(
            id = "block_sync_taps",
            displayName = "Sync: Triple Taps",
            startEvent = EventLabels.SYNC_TAPS,
            endEvent = EventLabels.SYNC_TAPS,
            defaultDurationSec = 30,
            description = "Tap watch against chest strap 3 times",
            category = BlockCategory.SYNC,
        ),
        ProtocolBlock(
            id = "block_sync_claps",
            displayName = "Sync: Five Claps",
            startEvent = EventLabels.SYNC_CLAPS,
            endEvent = EventLabels.SYNC_CLAPS,
            defaultDurationSec = 30,
            description = "Clap 5 times while all devices record",
            category = BlockCategory.SYNC,
        ),
        ProtocolBlock(
            id = "block_sync_sitstand",
            displayName = "Sync: Sit-to-Stand",
            startEvent = EventLabels.SYNC_SIT_STAND,
            endEvent = EventLabels.SYNC_SIT_STAND,
            defaultDurationSec = 60,
            description = "Perform 3 sit-to-stand movements",
            category = BlockCategory.SYNC,
        ),

        // Block 2: Resting baseline
        ProtocolBlock(
            id = "block_2_rest",
            displayName = "Resting Baseline",
            startEvent = EventLabels.REST_START,
            endEvent = EventLabels.REST_END,
            defaultDurationSec = 300,
            description = "Seated, arms supported, quiet, normal breathing (5 min)",
            category = BlockCategory.REST,
        ),

        // Block 3: Paced breathing
        ProtocolBlock(
            id = "block_3_breathing",
            displayName = "Paced Breathing",
            startEvent = EventLabels.PACED_BREATHING_START,
            endEvent = EventLabels.PACED_BREATHING_END,
            defaultDurationSec = 180,
            description = "Inhale 4s, Exhale 6s (6 breaths/min) for 3 min",
            category = BlockCategory.BREATHING,
        ),

        // Block 4: Cognitive stress task
        ProtocolBlock(
            id = "block_4_stress",
            displayName = "Stress Task",
            startEvent = EventLabels.STRESS_TASK_START,
            endEvent = EventLabels.STRESS_TASK_END,
            defaultDurationSec = 420,
            description = "Mental arithmetic: count backwards from 2026 by 7 (5-7 min)",
            category = BlockCategory.STRESS,
        ),

        // Block 5: Movement artifact matrix
        ProtocolBlock(
            id = "block_5_motion_still",
            displayName = "Motion: Still Hand",
            startEvent = EventLabels.MOTION_STILL,
            endEvent = EventLabels.MOTION_STILL,
            defaultDurationSec = 60,
            description = "Still hand on lap (60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_wrist",
            displayName = "Motion: Wrist Flex/Ext",
            startEvent = EventLabels.MOTION_WRIST_FLEX,
            endEvent = EventLabels.MOTION_WRIST_FLEX,
            defaultDurationSec = 60,
            description = "Wrist flexion/extension (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_tremor",
            displayName = "Motion: Tremor-like Shaking",
            startEvent = EventLabels.MOTION_TREMOR_LIKE,
            endEvent = EventLabels.MOTION_TREMOR_LIKE,
            defaultDurationSec = 60,
            description = "Hand shaking 3-6 Hz (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_tapping",
            displayName = "Motion: Finger Tapping",
            startEvent = EventLabels.MOTION_TAPPING,
            endEvent = EventLabels.MOTION_TAPPING,
            defaultDurationSec = 60,
            description = "Finger tapping (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_writing",
            displayName = "Motion: Writing Gesture",
            startEvent = EventLabels.MOTION_WRITING,
            endEvent = EventLabels.MOTION_WRITING,
            defaultDurationSec = 60,
            description = "Writing gesture (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_scrolling",
            displayName = "Motion: Phone Scrolling",
            startEvent = EventLabels.MOTION_SCROLLING,
            endEvent = EventLabels.MOTION_SCROLLING,
            defaultDurationSec = 60,
            description = "Phone scrolling (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_armswing",
            displayName = "Motion: Arm Swing",
            startEvent = EventLabels.MOTION_ARM_SWING,
            endEvent = EventLabels.MOTION_ARM_SWING,
            defaultDurationSec = 60,
            description = "Arm swing while standing (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_walkplace",
            displayName = "Motion: Walking in Place",
            startEvent = EventLabels.MOTION_WALK_IN_PLACE,
            endEvent = EventLabels.MOTION_WALK_IN_PLACE,
            defaultDurationSec = 60,
            description = "Walking in place (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_rubbing",
            displayName = "Motion: Hand Rubbing",
            startEvent = EventLabels.MOTION_RUBBING,
            endEvent = EventLabels.MOTION_RUBBING,
            defaultDurationSec = 60,
            description = "Hand rubbing (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),
        ProtocolBlock(
            id = "block_5_motion_speech",
            displayName = "Motion: Speech/Coughing",
            startEvent = EventLabels.MOTION_SPEECH_GESTURE,
            endEvent = EventLabels.MOTION_SPEECH_GESTURE,
            defaultDurationSec = 60,
            description = "Coughing/speaking movement (30-60 sec)",
            category = BlockCategory.MOVEMENT,
        ),

        // Block 6: Tremor/hand motor tasks
        ProtocolBlock(
            id = "block_6_tremor_rest",
            displayName = "Tremor: Rest",
            startEvent = EventLabels.TREMOR_REST_START,
            endEvent = EventLabels.TREMOR_REST_END,
            defaultDurationSec = 60,
            description = "Hands resting on thighs (60 sec)",
            category = BlockCategory.TREMOR,
        ),
        ProtocolBlock(
            id = "block_6_tremor_postural",
            displayName = "Tremor: Postural",
            startEvent = EventLabels.TREMOR_POSTURAL_START,
            endEvent = EventLabels.TREMOR_POSTURAL_END,
            defaultDurationSec = 60,
            description = "Arms extended forward (30-60 sec)",
            category = BlockCategory.TREMOR,
        ),
        ProtocolBlock(
            id = "block_6_tremor_ftn",
            displayName = "Tremor: Finger-to-Nose",
            startEvent = EventLabels.TREMOR_FTN_START,
            endEvent = EventLabels.TREMOR_FTN_END,
            defaultDurationSec = 60,
            description = "30 sec per side (60 sec total)",
            category = BlockCategory.TREMOR,
        ),
        ProtocolBlock(
            id = "block_6_tremor_tap",
            displayName = "Tremor: Finger Tapping",
            startEvent = EventLabels.TREMOR_TAP_START,
            endEvent = EventLabels.TREMOR_TAP_END,
            defaultDurationSec = 30,
            description = "Rapid finger tapping (30 sec)",
            category = BlockCategory.TREMOR,
        ),

        // Block 7: Gait tasks
        ProtocolBlock(
            id = "block_7_gait_normal",
            displayName = "Gait: Normal Walking",
            startEvent = EventLabels.GAIT_NORMAL_START,
            endEvent = EventLabels.GAIT_NORMAL_END,
            defaultDurationSec = 180,
            description = "Walk 10m back and forth for 2-3 min",
            category = BlockCategory.GAIT,
        ),
        ProtocolBlock(
            id = "block_7_gait_slow",
            displayName = "Gait: Slow Walking",
            startEvent = EventLabels.GAIT_SLOW_START,
            endEvent = EventLabels.GAIT_SLOW_END,
            defaultDurationSec = 60,
            description = "Slow walking (1 min)",
            category = BlockCategory.GAIT,
        ),
        ProtocolBlock(
            id = "block_7_gait_fast",
            displayName = "Gait: Fast Walking",
            startEvent = EventLabels.GAIT_FAST_START,
            endEvent = EventLabels.GAIT_FAST_END,
            defaultDurationSec = 60,
            description = "Fast walking (1 min, if safe)",
            category = BlockCategory.GAIT,
        ),
        ProtocolBlock(
            id = "block_7_gait_turn",
            displayName = "Gait: Turning",
            startEvent = EventLabels.GAIT_TURN_START,
            endEvent = EventLabels.GAIT_TURN_END,
            defaultDurationSec = 120,
            description = "180-degree turns, 10 turns (if safe)",
            category = BlockCategory.GAIT,
        ),
        ProtocolBlock(
            id = "block_7_gait_dual",
            displayName = "Gait: Dual-Task Walking",
            startEvent = EventLabels.GAIT_DUAL_TASK_START,
            endEvent = EventLabels.GAIT_DUAL_TASK_END,
            defaultDurationSec = 120,
            description = "Walk while counting backwards or naming animals",
            category = BlockCategory.GAIT,
        ),

        // Block 8: Haptic tests
        ProtocolBlock(
            id = "block_8_haptic_latency",
            displayName = "Haptic: Latency Test",
            startEvent = EventLabels.HAPTIC_LATENCY_TEST,
            endEvent = EventLabels.HAPTIC_LATENCY_TEST,
            defaultDurationSec = 60,
            description = "Tap watch, measure vibration delay (target <150ms)",
            category = BlockCategory.HAPTIC,
        ),
        ProtocolBlock(
            id = "block_8_haptic_gait",
            displayName = "Haptic: Gait Cue",
            startEvent = EventLabels.HAPTIC_GAIT_CUE_START,
            endEvent = EventLabels.HAPTIC_GAIT_CUE_END,
            defaultDurationSec = 180,
            description = "Haptic pulses at estimated cadence",
            category = BlockCategory.HAPTIC,
        ),
        ProtocolBlock(
            id = "block_8_haptic_breathing",
            displayName = "Haptic: Breathing Cue",
            startEvent = EventLabels.HAPTIC_BREATHING_START,
            endEvent = EventLabels.HAPTIC_BREATHING_END,
            defaultDurationSec = 180,
            description = "Haptic pattern for 6 breaths/min",
            category = BlockCategory.HAPTIC,
        ),
        ProtocolBlock(
            id = "block_8_haptic_tremor",
            displayName = "Haptic: Tremor Cue",
            startEvent = EventLabels.HAPTIC_TREMOR_CUE_START,
            endEvent = EventLabels.HAPTIC_TREMOR_CUE_END,
            defaultDurationSec = 60,
            description = "Low-frequency grounding pulses (2-3 Hz)",
            category = BlockCategory.HAPTIC,
        ),

        // Session end
        ProtocolBlock(
            id = "block_session_end",
            displayName = "Session End",
            startEvent = EventLabels.SESSION_END,
            endEvent = EventLabels.SESSION_END,
            defaultDurationSec = 0,
            description = "End of session",
            category = BlockCategory.SETUP,
        ),
    )

    /** Get blocks grouped by category */
    fun getByCategory(): Map<BlockCategory, List<ProtocolBlock>> {
        return ALL.groupBy { it.category }
    }

    /** Get blocks for a specific category */
    fun getByCategory(category: BlockCategory): List<ProtocolBlock> {
        return ALL.filter { it.category == category }
    }
}
