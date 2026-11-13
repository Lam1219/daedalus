package com.medicina.shared.pilot

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Home pilot rules, ported line for line from daedalus_watch_kit/daedalus_watch_kit/pilot.py
 * (pilot-1.0). The golden vectors in daedalus_watch_kit/golden/pilot/pilot.json are the contract
 * (PilotGoldenTest in the watch module).
 *
 * Every press gets a beat: nothing here returns "no beat".
 */
enum class Place(val wire: String) {
    WRIST("wrist"), EARBUDS("earbuds"), POCKET("pocket");

    companion object {
        fun fromWire(s: String?): Place? = entries.firstOrNull { it.wire == s }
    }
}

enum class CheckIn(val wire: String) {
    HELPED("helped"), NOT_HELPED("not_helped"), ALREADY_MOVING("already_moving"), NOT_FELT("not_felt");

    companion object {
        fun fromWire(s: String?): CheckIn? = entries.firstOrNull { it.wire == s }
    }
}

object Pilot {
    const val VERSION = "pilot-1.0"

    /** Fixed order, the same as Python's PLACES. */
    val PLACES = listOf(Place.WRIST, Place.EARBUDS, Place.POCKET)

    const val LEARNING_DAYS = 7
    const val PILOT_DAYS = 21
    const val MAX_SEED = 1L shl 31

    const val MAX_CHECKINS_PER_DAY = 6
    const val REMINDER_GAP_MS = 60 * 60 * 1000L
    const val PHONE_CARRIED_WINDOW_S = 60

    const val STUDY_MAX_CUE_S = 10 * 60
    const val BUFFER_BEFORE_S = 30
    const val BUFFER_AFTER_S = 60

    private const val GAMMA = 0x9E3779B97F4A7C15uL

    /** One SplitMix64 output for input x. All arithmetic wraps mod 2^64 (ULong). */
    fun splitmix64(x: ULong): ULong {
        var z = x + GAMMA
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** The 64-bit random number for this person's nth press (stateless). */
    fun draw(seed: Long, pressIndex: Long): ULong =
        splitmix64(seed.toULong() + pressIndex.toULong() * GAMMA)

    fun availablePlaces(earbudsConnected: Boolean, phoneCarried: Boolean): List<Place> {
        val out = mutableListOf(Place.WRIST)
        if (earbudsConnected) out.add(Place.EARBUDS)
        if (phoneCarried) out.add(Place.POCKET)
        return out
    }

    /**
     * Where this press plays its beat. [dayIndex] is whole local calendar days since the enrolment
     * day ([dayIndex] below). Before enrolment and from day 21 on: the wrist.
     */
    fun choosePlace(seed: Long, pressIndex: Long, dayIndex: Long, earbudsConnected: Boolean, phoneCarried: Boolean): Place {
        require(seed in 0 until MAX_SEED) { "seed out of range" }
        require(pressIndex >= 0) { "press_index must be >= 0" }
        if (dayIndex < 0 || dayIndex >= PILOT_DAYS) return Place.WRIST
        val places = availablePlaces(earbudsConnected, phoneCarried)
        val n = places.size
        if (dayIndex < LEARNING_DAYS) return places[(pressIndex % n).toInt()]
        return places[(draw(seed, pressIndex) % n.toULong()).toInt()]
    }

    /** Ask the one-tap check-in after a beat stops unless 6 were already asked today. */
    fun shouldAskCheckin(askedToday: Int): Boolean = askedToday < MAX_CHECKINS_PER_DAY

    /** B10: remind the person to pocket the phone after a stand-up (see pilot.py). */
    fun shouldRemindPocket(
        nowMs: Long, stoodUp: Boolean, phoneStepsLastMin: Int, phoneMovedLastMin: Boolean,
        lastReminderMs: Long?, beatPlaying: Boolean, enabled: Boolean,
    ): Boolean {
        if (!enabled || !stoodUp || beatPlaying) return false
        if (phoneStepsLastMin > 0 || phoneMovedLastMin) return false
        return lastReminderMs == null || nowMs - lastReminderMs >= REMINDER_GAP_MS
    }

    /** Whole local calendar days from [enrolment] to [today] (0 on the enrolment day, negative before). */
    fun dayIndex(enrolment: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(enrolment, today)
}
