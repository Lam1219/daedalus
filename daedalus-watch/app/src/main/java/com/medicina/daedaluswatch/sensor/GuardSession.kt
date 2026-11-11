package com.medicina.daedaluswatch.sensor

import com.medicina.daedaluswatch.model.EventLabels

/**
 * Guard sessions (automatic cues in shadow mode, Documentation/automatic-cues-design.md 2.2 and
 * 2.7): the session modes, and the rows a guard writes into its session CSV.
 *
 * A guard records every motion sample and runs [GuardEngine] on it; nothing cues on its own yet.
 * Its CSV has the usual columns plus two trailing ones, `event` and `event_notes`:
 * - sample rows leave the two event cells empty;
 * - event rows fill only `timestamp_ms`, `event` and `event_notes` (the other nine cells blank).
 * The phone and daedalus_watch_kit read the columns by header name.
 *
 * The Apple Watch writes the same rows (CueController / GuardRows.swift). Change both together.
 */
object GuardSession {
    const val MODE_WALK_GUARD = "walk_guard"
    const val MODE_REST_GUARD = "rest_guard"

    /** A battery reading is logged at the start, every 5 minutes, and at the end. */
    const val BATTERY_INTERVAL_MS = 5 * 60 * 1000L

    const val HEADER =
        "timestamp_ms,heart_rate_bpm,ax,ay,az,gx,gy,gz,breathing_rate_brpm,spo2_percent,event,event_notes\n"

    fun isGuard(mode: String?): Boolean = mode == MODE_WALK_GUARD || mode == MODE_REST_GUARD

    /** The engine mode for a guard session mode. */
    fun engineMode(mode: String): String =
        if (mode == MODE_WALK_GUARD) GuardEngine.MODE_WALK else GuardEngine.MODE_REST

    /** One motion sample; breathing rate, SpO2 and the event cells stay empty in a guard. */
    fun sampleRow(tMs: Long, heartRate: Float, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float): String =
        "$tMs,$heartRate,$ax,$ay,$az,$gx,$gy,$gz,,,,\n"

    /** An event: time, nine blank cells, then the event name and its notes (which never hold a comma). */
    fun eventRow(e: GuardEvent): String = "${e.epochMs},,,,,,,,,,${e.name},${e.notes}\n"

    /** [percent] 0–100, or -1 when unknown; [charging] = on the charger (a full battery still plugged in counts). */
    fun battery(tMs: Long, percent: Int, charging: Boolean) =
        GuardEvent(tMs, EventLabels.GUARD_BATTERY, "type=guard;battery_pct=$percent;charging=$charging")

    /** "I'm stuck" during a guard: the walking rhythm starts inside it (a labelled freeze for later evaluation). */
    fun stuckCueStart(tMs: Long, bpm: Int) =
        GuardEvent(tMs, EventLabels.HAPTIC_GAIT_CUE_START, "type=manual;trigger=im_stuck;bpm=$bpm")

    fun stuckCueEnd(tMs: Long) = GuardEvent(tMs, EventLabels.HAPTIC_GAIT_CUE_END, "type=manual;trigger=stop")
}
