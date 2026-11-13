package com.medicina.shared.pilot

import java.time.LocalDate
import java.util.Locale

/**
 * One "I'm stuck" press, written as one JSON file in the format of the docstring of
 * daedalus_watch_kit/daedalus_watch_kit/pilot_analysis.py (pilot-1.0). The fields after
 * `samples` are extras the analysis ignores; they say how the beat was played.
 */
data class PressRecord(
    val participant: String,
    val pressIndex: Long,
    val dayIndex: Long,
    /** The place actually used (where the beat started). */
    val place: Place,
    val earbudsConnected: Boolean,
    val phoneCarried: Boolean,
    val paceSpm: Int,
    val pressMs: Long,
    /** When the beat actually began, or null if it was never confirmed: never the press time,
     *  which would make the beat look faster than it was (H1). */
    val beatStartMs: Long?,
    val beatStopMs: Long?,
    val checkin: CheckIn?,
    val samples: MotionSamples,
    // Extras
    val placeDrawn: Place,
    val player: Player,
    /** How beat_start_ms was measured, for example "watch_motor" or "phone_audio_timestamp+rtt". */
    val beatStartSource: String,
    val fallbackReason: String? = null,
    val fallbackAtMs: Long? = null,
    val app: String = "wear-os",
    /** Finished after a crash or restart from the pending file written at the press (round 001, #5). */
    val recovered: Boolean = false,
) {
    /** Audio over Bluetooth: the codec delay after the output may not be in beat_start_ms (round 001, #7). */
    val btLatencyUnknown: Boolean get() = isBluetoothSource(beatStartSource)

    /** Sortable and unique per person: pilot_P03_00012_1794570000000.json */
    val fileName: String get() = "pilot_${participant}_${String.format(Locale.US, "%05d", pressIndex)}_$pressMs.json"

    fun toJson(): String {
        val sb = StringBuilder(64 * 1024)
        sb.append('{')
        field(sb, "version", str(Pilot.VERSION))
        field(sb, "participant", str(participant))
        field(sb, "press_index", pressIndex.toString())
        field(sb, "day_index", dayIndex.toString())
        field(sb, "place", str(place.wire))
        field(sb, "earbuds_connected", earbudsConnected.toString())
        field(sb, "phone_carried", phoneCarried.toString())
        field(sb, "pace_spm", paceSpm.toString())
        field(sb, "press_ms", pressMs.toString())
        field(sb, "beat_start_ms", beatStartMs?.toString() ?: "null")
        field(sb, "beat_stop_ms", beatStopMs?.toString() ?: "null")
        field(sb, "checkin", checkin?.let { str(it.wire) } ?: "null")
        sb.append("\"samples\":{\"t_ms\":[")
        samples.tMs.forEachIndexed { i, t -> if (i > 0) sb.append(','); sb.append(t) }
        sb.append("],\"acc\":")
        axes(sb, samples.acc)
        sb.append(",\"gyr\":")
        if (samples.gyr == null) sb.append("null") else axes(sb, samples.gyr)
        sb.append("},")
        field(sb, "place_drawn", str(placeDrawn.wire))
        field(sb, "player", str(player.wire))
        field(sb, "beat_start_source", str(beatStartSource))
        field(sb, "fallback_reason", fallbackReason?.let { str(it) } ?: "null")
        field(sb, "fallback_ms", fallbackAtMs?.toString() ?: "null")
        field(sb, "bt_latency_unknown", btLatencyUnknown.toString())
        field(sb, "recovered", recovered.toString())
        sb.append("\"app\":").append(str(app))
        sb.append('}')
        return sb.toString()
    }

    private fun field(sb: StringBuilder, k: String, v: String) {
        sb.append('"').append(k).append("\":").append(v).append(',')
    }

    private fun axes(sb: StringBuilder, a: Array<FloatArray>) {
        sb.append('[')
        a.forEachIndexed { c, xs ->
            if (c > 0) sb.append(',')
            sb.append('[')
            xs.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(num(x)) }
            sb.append(']')
        }
        sb.append(']')
    }

    companion object {
        /** ClickBeatPlayer marks a source "_bt" when the click track was routed to a Bluetooth device. */
        fun isBluetoothSource(source: String) = source.contains("_bt")

        /** JSON has no NaN or infinity; a sensor never gives them, but never write an invalid file. */
        fun num(x: Float): String = if (x.isFinite()) x.toString() else "0.0"

        fun str(s: String): String {
            val sb = StringBuilder("\"")
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
            return sb.append('"').toString()
        }
    }
}

/**
 * B5: check-ins asked on the current local day. [askedOn] is the local day of the last count.
 * Pure, so the daily limit is unit-tested; the watch keeps the two values in preferences.
 */
data class CheckinDay(val askedOn: LocalDate?, val asked: Int) {
    fun askedToday(today: LocalDate): Int = if (askedOn == today) asked else 0

    fun shouldAsk(today: LocalDate): Boolean = Pilot.shouldAskCheckin(askedToday(today))

    /** After asking (whether or not it gets an answer). */
    fun afterAsking(today: LocalDate): CheckinDay = CheckinDay(today, askedToday(today) + 1)
}
