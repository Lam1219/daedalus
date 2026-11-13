package com.medicina.shared.pilot

/** What plays the beat. Each place has one or two players; the wrist is always the watch's motor. */
enum class Player(val wire: String, val remote: Boolean) {
    WATCH_HAPTIC("watch_haptic", false),
    WATCH_AUDIO("watch_audio", false),
    PHONE_AUDIO("phone_audio", true),
    PHONE_VIBRATION("phone_vibration", true);

    val place: Place get() = when (this) {
        WATCH_HAPTIC -> Place.WRIST
        WATCH_AUDIO, PHONE_AUDIO -> Place.EARBUDS
        PHONE_VIBRATION -> Place.POCKET
    }
}

/** The draw for one press and who plays it. */
data class PressPlan(
    val drawn: Place,
    val player: Player,
    /** Earbuds connected to whichever device would play them, at the press. */
    val earbudsConnected: Boolean,
    val phoneCarried: Boolean,
)

object PressPlanner {
    /**
     * B2/B3: earbuds count as connected when the watch has them (the watch plays the clicks) or,
     * failing that, when the phone has them (the phone plays). Pocket needs the phone carried.
     */
    fun plan(
        settings: PilotSettings, pressIndex: Long, dayIndex: Long,
        watchEarbuds: Boolean, phoneEarbuds: Boolean, phoneCarried: Boolean,
    ): PressPlan {
        val earbuds = watchEarbuds || phoneEarbuds
        val drawn = Pilot.choosePlace(settings.seed, pressIndex, dayIndex, earbuds, phoneCarried)
        val player = when (drawn) {
            Place.WRIST -> Player.WATCH_HAPTIC
            Place.EARBUDS -> if (watchEarbuds) Player.WATCH_AUDIO else Player.PHONE_AUDIO
            Place.POCKET -> Player.PHONE_VIBRATION
        }
        return PressPlan(drawn, player, earbuds, phoneCarried)
    }
}

/** Starts and stops the players. Implemented by the watch's HapticService; faked in tests. */
interface BeatOutput {
    /**
     * Starts [player] at [bpm]. Calls [onStarted] with the epoch ms (watch clock) when the first
     * pulse or click actually played, and how that time was measured, or [onFailed] if it can't play. Either may be called on any
     * thread, synchronously or later. The watch's motor must never fail.
     */
    fun start(player: Player, bpm: Int, onStarted: (Long, String) -> Unit, onFailed: (String) -> Unit)
    fun stop(player: Player)
}

/**
 * Runs one press's beat and enforces "every press gets a beat": if the drawn player fails, or a
 * player doesn't confirm within [remoteTimeoutMs], or the player is lost mid-beat, the
 * watch's motor takes over at once. [schedule] runs a callback after a delay (main handler on the
 * watch, a manual clock in tests) and returns a cancel function.
 */
class BeatRunner(
    private val output: BeatOutput,
    private val schedule: (delayMs: Long, block: () -> Unit) -> (() -> Unit),
    private val remoteTimeoutMs: Long = REMOTE_TIMEOUT_MS,
) {
    var player: Player? = null
        private set
    /** The place where the beat actually started (null until it has). */
    var placeUsed: Place? = null
        private set
    var beatStartMs: Long? = null
        private set
    /** How [beatStartMs] was measured (see the player). */
    var beatStartSource: String? = null
        private set
    var fallbackReason: String? = null
        private set
    /** Epoch ms of a mid-beat switch to the wrist (the place where the beat started is kept). */
    var fallbackAtMs: Long? = null
        private set

    private var bpm = 0
    private var stopped = false
    private var cancelTimeout: (() -> Unit)? = null
    private var attempt = 0

    @Synchronized
    fun start(plan: PressPlan, bpm: Int) {
        this.bpm = bpm
        launch(plan.player)
    }

    private fun launch(p: Player) {
        val id = ++attempt
        player = p
        // Any player but the motor must confirm its first click or pulse in time (a hung audio
        // output or an unreachable phone would otherwise leave the press silent).
        if (p != Player.WATCH_HAPTIC) {
            cancelTimeout = schedule(remoteTimeoutMs) { failed(id, "no_reply") }
        }
        output.start(p, bpm, onStarted = { t, src -> started(id, t, src) }, onFailed = { r -> failed(id, r) })
    }

    @Synchronized
    private fun started(id: Int, tMs: Long, source: String) {
        if (id != attempt || stopped || beatStartMs != null) return
        cancelTimeout?.invoke(); cancelTimeout = null
        beatStartMs = tMs
        beatStartSource = source
        placeUsed = player?.place
    }

    @Synchronized
    private fun failed(id: Int, reason: String) {
        if (id != attempt || stopped) return
        if (beatStartMs != null) return lost(reason)
        cancelTimeout?.invoke(); cancelTimeout = null
        val p = player ?: return
        if (p == Player.WATCH_HAPTIC) return // the motor has no fallback; it doesn't report failures
        output.stop(p)
        fallbackReason = reason
        launch(Player.WATCH_HAPTIC)
    }

    /** The player stopped working after the beat started (earbuds dropped, phone gone): wrist now. */
    @Synchronized
    fun lost(reason: String, nowMs: Long = System.currentTimeMillis()) {
        val p = player ?: return
        if (stopped || p == Player.WATCH_HAPTIC) return
        output.stop(p)
        fallbackReason = reason
        fallbackAtMs = nowMs
        ++attempt // late replies from the lost player are ignored
        player = Player.WATCH_HAPTIC
        output.start(Player.WATCH_HAPTIC, bpm, onStarted = { _, _ -> }, onFailed = {})
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        cancelTimeout?.invoke(); cancelTimeout = null
        player?.let { output.stop(it) }
    }

    companion object {
        /** How long a phone player has to confirm its first click or pulse (plan B1: within 1 s). */
        const val REMOTE_TIMEOUT_MS = 1_000L
    }
}

/**
 * Clock alignment for a beat played by the phone. The watch sent the request at [watchSentMs] and
 * got the reply at [watchReceivedMs] (watch clock). The phone reports, on its own clock, when it
 * received the request, when the first click or pulse played and when it sent the reply. Only
 * differences of the phone's clock are used, so the two clocks never need to agree. The one-way
 * delay is taken as half the round trip less the phone's own time (as NTP does).
 */
object BeatClock {
    fun beatStartOnWatch(watchSentMs: Long, watchReceivedMs: Long, phoneReceivedMs: Long, phoneStartMs: Long, phoneSentMs: Long): Long {
        val phoneBusy = (phoneSentMs - phoneReceivedMs).coerceAtLeast(0)
        val oneWay = ((watchReceivedMs - watchSentMs - phoneBusy) / 2).coerceAtLeast(0)
        val startBeforeSend = (phoneSentMs - phoneStartMs).coerceAtLeast(0)
        return watchReceivedMs - oneWay - startBeforeSend
    }
}
