package com.medicina.shared.pilot

import java.io.File

/**
 * Round 001, #5: what is known about a press, saved at the press and updated as the beat goes on,
 * so a crash or restart mid-beat still leaves a press file. Plain `key=value` lines. The file is
 * named `pending_…`, so neither the analysis (`*.json`) nor the transfer to the phone (`pilot_*`)
 * picks it up.
 */
data class PendingPress(
    val participant: String,
    val pressIndex: Long,
    val dayIndex: Long,
    val placeDrawn: Place,
    val player: Player,
    val placeUsed: Place?,
    val earbudsConnected: Boolean,
    val phoneCarried: Boolean,
    val paceSpm: Int,
    val pressMs: Long,
    val beatStartMs: Long? = null,
    val beatStartSource: String? = null,
    val beatStopMs: Long? = null,
    val fallbackReason: String? = null,
    val fallbackAtMs: Long? = null,
) {
    /** The press file this pending press becomes. */
    val pressFileName: String get() = toRecord(MotionSamples.EMPTY, null).fileName
    val fileName: String get() = "pending_" + pressFileName.removeSuffix(".json") + ".txt"

    fun encode(): String = listOf(
        "participant" to participant, "press_index" to pressIndex, "day_index" to dayIndex,
        "place_drawn" to placeDrawn.wire, "player" to player.wire, "place_used" to placeUsed?.wire,
        "earbuds_connected" to earbudsConnected, "phone_carried" to phoneCarried, "pace_spm" to paceSpm,
        "press_ms" to pressMs, "beat_start_ms" to beatStartMs, "beat_start_source" to beatStartSource,
        "beat_stop_ms" to beatStopMs, "fallback_reason" to fallbackReason, "fallback_ms" to fallbackAtMs,
    ).filter { it.second != null }.joinToString("\n") { "${it.first}=${it.second}" } + "\n"

    /**
     * The press file. A recovered press has whatever motion is still available (or none), no
     * check-in, and the place where the beat started if it was confirmed, else the drawn player's.
     */
    fun toRecord(samples: MotionSamples, checkin: CheckIn?, recovered: Boolean = false) = PressRecord(
        participant = participant, pressIndex = pressIndex, dayIndex = dayIndex,
        place = placeUsed ?: player.place, earbudsConnected = earbudsConnected, phoneCarried = phoneCarried,
        paceSpm = paceSpm, pressMs = pressMs, beatStartMs = beatStartMs, beatStopMs = beatStopMs,
        checkin = checkin, samples = samples, placeDrawn = placeDrawn, player = player,
        beatStartSource = beatStartSource ?: "missing", fallbackReason = fallbackReason, fallbackAtMs = fallbackAtMs,
        recovered = recovered,
    )

    companion object {
        const val PREFIX = "pending_"

        fun decode(text: String): PendingPress? = runCatching {
            val m = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            PendingPress(
                participant = m.getValue("participant"),
                pressIndex = m.getValue("press_index").toLong(),
                dayIndex = m.getValue("day_index").toLong(),
                placeDrawn = Place.fromWire(m["place_drawn"])!!,
                player = Player.entries.first { it.wire == m["player"] },
                placeUsed = Place.fromWire(m["place_used"]),
                earbudsConnected = m["earbuds_connected"] == "true",
                phoneCarried = m["phone_carried"] == "true",
                paceSpm = m.getValue("pace_spm").toInt(),
                pressMs = m.getValue("press_ms").toLong(),
                beatStartMs = m["beat_start_ms"]?.toLong(),
                beatStartSource = m["beat_start_source"],
                beatStopMs = m["beat_stop_ms"]?.toLong(),
                fallbackReason = m["fallback_reason"],
                fallbackAtMs = m["fallback_ms"]?.toLong(),
            )
        }.getOrNull()
    }
}

/**
 * Round 001, #14: writes pilot files without ever crashing the app or replacing an existing file.
 * Writes to `<name>.part`, then renames; if the rename fails it copies instead; on any error it
 * tries once more, then gives up and returns null (the caller logs it).
 */
object PilotFileWriter {
    fun write(dir: File, name: String, text: String, replace: Boolean = false, log: (String) -> Unit = {}): File? {
        repeat(2) { attempt ->
            val result = runCatching { writeOnce(dir, name, text, replace) }
            result.getOrNull()?.let { return it }
            log("write $name failed (attempt ${attempt + 1}): ${result.exceptionOrNull()?.message}")
        }
        return null
    }

    private fun writeOnce(dir: File, name: String, text: String, replace: Boolean): File {
        dir.mkdirs()
        var target = File(dir, name)
        if (!replace) target = free(dir, name)
        val tmp = File(dir, "${target.name}.part")
        tmp.writeText(text)
        if (replace && target.isFile) target.delete()
        if (!tmp.renameTo(target)) {
            // Some file systems refuse a rename; copy, and keep the .part only if the copy failed.
            check(!target.exists() || replace) { "target appeared: ${target.name}" }
            target.writeText(text)
            tmp.delete()
        }
        check(target.isFile && target.length() > 0) { "nothing written to ${target.name}" }
        return target
    }

    /** `name`, or `name` with -2, -3… before the extension when it is taken: both files are kept. */
    fun free(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 2
        val stem = name.substringBeforeLast('.')
        val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
        while (f.exists()) f = File(dir, "$stem-${n++}$ext")
        return f
    }
}
