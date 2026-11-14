package com.medicina.shared.pilot

/** Watch–phone messages of the home pilot (Wearable MessageClient paths and payloads). */
object PilotLink {
    /** Phone → watch: [PilotSettings.encode]. */
    const val SETTINGS_PATH = "/pilot/settings"
    /** Phone → watch: [PhoneStatus.encode], pushed on changes and in reply to a query. */
    const val STATUS_PATH = "/pilot/status"
    /** Watch → phone: a query id; the phone flushes its sensors and replies on [STATUS_PATH]. */
    const val QUERY_PATH = "/pilot/query"
    /** Both ways: beat start / stop on the phone and its replies ([BeatMessage]). */
    const val BEAT_PATH = "/pilot/beat"
    /** Phone → watch: delete the watch's pilot files (round 007, #4). */
    const val DELETE_PATH = "/pilot/delete"
    /** Watch → phone: press and battery files, by ChannelClient at FILE_PATH/<file name>. */
    const val FILE_PATH = "/pilot/file"
}

/**
 * What the phone knows that the watch needs at a press or a stand-up. [lastStepAgoMs] is how long
 * before sending the phone's step counter last counted a step (null: none since the service
 * started), so the two clocks never need to agree. [moved] is null in pushed updates (only a
 * query measures it).
 */
data class PhoneStatus(
    val earbuds: Boolean,
    val lastStepAgoMs: Long?,
    val moved: Boolean?,
    val queryId: String = "",
) {
    fun encode(): String = listOf(
        if (earbuds) "1" else "0",
        lastStepAgoMs?.toString() ?: "-1",
        when (moved) { null -> "-1"; true -> "1"; false -> "0" },
        queryId,
    ).joinToString("|")

    companion object {
        fun decode(s: String): PhoneStatus? {
            val p = s.split("|")
            if (p.size < 3) return null
            val ago = p[1].toLongOrNull() ?: return null
            return PhoneStatus(
                earbuds = p[0] == "1",
                lastStepAgoMs = ago.takeIf { it >= 0 },
                moved = when (p[2]) { "1" -> true; "0" -> false; else -> null },
                queryId = p.getOrElse(3) { "" },
            )
        }
    }
}

/** Beat messages on [PilotLink.BEAT_PATH]. Times are each sender's own epoch ms. */
sealed class BeatMessage {
    abstract fun encode(): String

    data class Start(val pressId: String, val player: Player, val bpm: Int, val maxMs: Long) : BeatMessage() {
        override fun encode() = "start|$pressId|${player.wire}|$bpm|$maxMs"
    }
    data class Stop(val pressId: String) : BeatMessage() {
        override fun encode() = "stop|$pressId"
    }
    /** The person paused or resumed the cue on the watch (round 001, #24). */
    data class Pause(val pressId: String, val paused: Boolean) : BeatMessage() {
        override fun encode() = (if (paused) "pause|" else "resume|") + pressId
    }
    /** The first click or pulse played at [startMs]; [receivedMs] and [sentMs] for [BeatClock]. */
    data class Started(val pressId: String, val receivedMs: Long, val startMs: Long, val sentMs: Long, val source: String) : BeatMessage() {
        override fun encode() = "started|$pressId|$receivedMs|$startMs|$sentMs|$source"
    }
    /** Couldn't start, or stopped working mid-beat. */
    data class Failed(val pressId: String, val reason: String) : BeatMessage() {
        override fun encode() = "failed|$pressId|$reason"
    }

    companion object {
        fun decode(s: String): BeatMessage? {
            val p = s.split("|")
            return when (p.firstOrNull()) {
                "start" -> {
                    val player = Player.entries.firstOrNull { it.wire == p.getOrNull(2) } ?: return null
                    Start(p.getOrNull(1) ?: return null, player, p.getOrNull(3)?.toIntOrNull() ?: return null, p.getOrNull(4)?.toLongOrNull() ?: return null)
                }
                "stop" -> Stop(p.getOrNull(1) ?: return null)
                "pause" -> Pause(p.getOrNull(1) ?: return null, true)
                "resume" -> Pause(p.getOrNull(1) ?: return null, false)
                "started" -> if (p.size < 6) null else Started(
                    p[1], p[2].toLongOrNull() ?: return null, p[3].toLongOrNull() ?: return null, p[4].toLongOrNull() ?: return null, p[5],
                )
                "failed" -> Failed(p.getOrNull(1) ?: return null, p.getOrNull(2) ?: "unknown")
                else -> null
            }
        }
    }
}
