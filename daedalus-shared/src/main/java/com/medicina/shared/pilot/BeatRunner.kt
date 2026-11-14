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
     * Owner, round 008: Android earbuds are cut from the pilot. Earbuds always count as not
     * connected here, whatever is paired, so the earbuds place is never drawn on Android and no
     * Android audio player starts for a pilot press. Earbuds are an iPhone-only place in the pilot.
     * The audio code (ClickBeatPlayer, the WATCH_AUDIO / PHONE_AUDIO players) stays, unreachable.
     */
    const val ANDROID_EARBUDS_IN_PILOT = false

    /** B3: wrist always, pocket when the phone is carried; earbuds never on Android (see above). */
    fun plan(
        settings: PilotSettings, pressIndex: Long, dayIndex: Long,
        watchEarbuds: Boolean, phoneEarbuds: Boolean, phoneCarried: Boolean,
    ): PressPlan {
        val watchEar = ANDROID_EARBUDS_IN_PILOT && watchEarbuds
        val earbuds = ANDROID_EARBUDS_IN_PILOT && (watchEarbuds || phoneEarbuds)
        val drawn = Pilot.choosePlace(settings.seed, pressIndex, dayIndex, earbuds, phoneCarried)
        val player = when (drawn) {
            Place.WRIST -> Player.WATCH_HAPTIC
            Place.EARBUDS -> if (watchEar) Player.WATCH_AUDIO else Player.PHONE_AUDIO
            Place.POCKET -> Player.PHONE_VIBRATION
        }
        return PressPlan(drawn, player, earbuds, phoneCarried)
    }
}

/** Starts and stops the players. Implemented by the watch's HapticService; faked in tests. */
interface BeatOutput {
    /**
     * Starts [player] at [bpm]. Calls [onStarted] as soon as it is actually playing, with the
     * epoch ms (watch clock) of the first pulse or click when it is known, or null when it isn't
     * known yet, and how that time was (or wasn't) measured. A player may call it a second time
     * with the measured start (round 002, #8: audio confirms when its first frames are written and
     * reports the output timestamp later). [onFailed] if it can't play. Either may be called on
     * any thread, synchronously or later. The watch's motor must never fail.
     */
    fun start(player: Player, bpm: Int, onStarted: (Long?, String) -> Unit, onFailed: (String) -> Unit)
    fun stop(player: Player)
    /** The person paused or resumed the cue: silence the player, then go on (round 001, #24). */
    fun pause(player: Player) {}
    fun resume(player: Player) {}
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
    /** Called once the player has confirmed (for the pending press file, round 001 #5). */
    @Volatile var onConfirmed: (() -> Unit)? = null
    /** The player said it is playing. [beatStartMs] can still be null: see [started]. */
    var confirmed = false
        private set
    var paused = false
        private set
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
        // One wait for every player (round 002, #8): audio confirms when it is playing, not when
        // its timestamp arrives, so 1 s is enough for the watch's own earbuds too.
        if (p != Player.WATCH_HAPTIC) {
            cancelTimeout = schedule(remoteTimeoutMs) { failed(id, "no_reply") }
        }
        output.start(p, bpm, onStarted = { t, src -> started(id, t, src) }, onFailed = { r -> failed(id, r) })
    }

    @Synchronized
    private fun started(id: Int, startMs: Long?, source: String) {
        if (id != attempt || stopped) return
        // Round 003, #5: a play() call time (an older phone build) is never a start.
        val tMs = if (source.contains("audio_play_call")) null else startMs
        if (confirmed) {
            // The measured start, after the confirmation: record it (or why there is none).
            if (beatStartMs == null) {
                beatStartMs = tMs
                beatStartSource = source
                onConfirmed?.invoke()
            }
            return
        }
        cancelTimeout?.invoke(); cancelTimeout = null
        confirmed = true
        // Never a request time: null until a real output time is reported (round 001 #7, round 002 #8).
        beatStartMs = tMs
        beatStartSource = source
        placeUsed = player?.place
        onConfirmed?.invoke()
    }

    @Synchronized
    private fun failed(id: Int, reason: String) {
        if (id != attempt || stopped) return
        if (confirmed) return lost(reason)
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
    fun pause() {
        if (stopped || paused) return
        paused = true
        player?.let { output.pause(it) }
    }

    @Synchronized
    fun resume() {
        if (stopped || !paused) return
        paused = false
        player?.let { output.resume(it) }
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
