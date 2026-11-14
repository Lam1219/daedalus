package com.medicina.shared.pilot

import java.time.LocalDate

/**
 * The home pilot settings, set on the phone (Profile → Home pilot) and sent to the watch.
 * With [enabled] false the apps behave exactly as without the pilot.
 */
data class PilotSettings(
    val enabled: Boolean = false,
    /** Pilot code such as "P03", never a name. */
    val participant: String = "",
    /** 0 <= seed < 2^31. */
    val seed: Long = 0,
    /** Enrolment day, as LocalDate.toEpochDay(). */
    val enrolmentEpochDay: Long = 0,
    /** B10 "Phone in your front pocket?" reminder after a stand-up. */
    val pocketReminder: Boolean = true,
    /**
     * Round 004 (owner, 2026-11-13), redesigned in round 006 (A): the earbuds place is paused on
     * Android until the physio's earbuds device check passed, and that check belongs to one
     * participant: this holds "<code>|<seed>" of the participant it was done for, or "".
     * Earbuds count only while it equals the current code and seed ([earbudsChecked]), so a new
     * code or seed can never inherit it, with no clearing logic to get wrong.
     */
    val earbudsCheckedFor: String = "",
) {
    val enrolment: LocalDate get() = LocalDate.ofEpochDay(enrolmentEpochDay)

    /** Enough to run the pilot: a valid code and seed. */
    val isValid: Boolean get() = isValidCode(participant) && isValidSeed(seed)

    /** Study mode is on and usable. */
    val active: Boolean get() = enabled && isValid

    /** This participant's identity, the value [earbudsCheckedFor] must equal. */
    val identityKey: String get() = "$participant|$seed"

    /** Earbuds may be drawn: the check was done for exactly this code and seed. */
    val earbudsChecked: Boolean get() = isValid && earbudsCheckedFor == identityKey

    /** Marks (or clears) the earbuds check for the current code and seed. */
    fun withEarbudsChecked(checked: Boolean): PilotSettings = copy(earbudsCheckedFor = if (checked) identityKey else "")

    /**
     * The message the phone sends to the watch on [PilotLink.SETTINGS_PATH]. Study on is sent as
     * "2" (round 006, #5): a watch build from before round 004 accepts only "0" and "1", so it
     * rejects the whole message and never turns study mode on with the paused earbuds code.
     */
    fun encode(): String = listOf(
        if (enabled) ON else "0", participant, seed.toString(), enrolmentEpochDay.toString(), if (pocketReminder) "1" else "0",
        earbudsCheckedFor.replace('|', ':'),
    ).joinToString("|")

    /**
     * Round 007, #1: the messages the phone sends, in order. With study mode on, an "off" copy
     * goes first, marked as a precursor in a seventh field. A watch from before round 006 accepts
     * the "off" (it reads only the first five or six fields), turns study mode off, then rejects
     * the "2|…" that follows, so it can never stay on with its old earbuds behaviour. A current
     * watch ignores the precursor ([isPrecursor]) and applies the "on", so its motion ring isn't
     * wiped on every resend.
     */
    fun wireMessages(): List<String> =
        if (enabled) listOf(copy(enabled = false).encode() + "|" + PRECURSOR, encode()) else listOf(encode())

    companion object {
        private val CODE = Regex("^[A-Za-z0-9-]{1,16}$")
        /** Seventh field of the "off" sent just before an "on" (round 007, #1). */
        const val PRECURSOR = "precursor"

        /** A current watch ignores the "off" that only exists to turn older watches off. */
        fun isPrecursor(message: String) = message.split("|").getOrNull(6) == PRECURSOR
        /** Study on, on the wire since round 006. */
        const val ON = "2"

        fun isValidCode(code: String) = CODE.matches(code)
        fun isValidSeed(seed: Long) = seed in 0 until Pilot.MAX_SEED

        /** Parses [encode]'s output; null when malformed. "1" (older phones) and "2" both mean on. */
        fun decode(s: String): PilotSettings? {
            val p = s.split("|")
            if (p.size < 5) return null
            val seed = p[2].toLongOrNull() ?: return null
            val day = p[3].toLongOrNull() ?: return null
            if (p[0] !in setOf("0", "1", ON)) return null
            // The sixth field is "<code>:<seed>" (round 006); anything else, or none, is "not checked".
            val checkedFor = p.getOrNull(5)?.takeIf { ':' in it }?.replace(':', '|') ?: ""
            return PilotSettings(p[0] != "0", p[1], seed, day, p[4] == "1", checkedFor)
        }
    }
}
