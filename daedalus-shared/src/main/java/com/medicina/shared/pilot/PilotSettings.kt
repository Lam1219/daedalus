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
     * Round 004 (owner, 2026-11-13): the earbuds place is paused on Android until the physio has
     * run the earbuds device check for this participant. Off: earbuds count as not connected for
     * drawing, on the watch and the phone, so no Android audio player starts for a pilot press.
     */
    val earbudsChecked: Boolean = false,
) {
    val enrolment: LocalDate get() = LocalDate.ofEpochDay(enrolmentEpochDay)

    /** Enough to run the pilot: a valid code and seed. */
    val isValid: Boolean get() = isValidCode(participant) && isValidSeed(seed)

    /** Study mode is on and usable. */
    val active: Boolean get() = enabled && isValid

    /** The message the phone sends to the watch on [PilotLink.SETTINGS_PATH]. */
    fun encode(): String = listOf(
        if (enabled) "1" else "0", participant, seed.toString(), enrolmentEpochDay.toString(), if (pocketReminder) "1" else "0",
        if (earbudsChecked) "1" else "0",
    ).joinToString("|")

    companion object {
        private val CODE = Regex("^[A-Za-z0-9-]{1,16}$")

        fun isValidCode(code: String) = CODE.matches(code)
        fun isValidSeed(seed: Long) = seed in 0 until Pilot.MAX_SEED

        /** Parses [encode]'s output; null when malformed. */
        fun decode(s: String): PilotSettings? {
            val p = s.split("|")
            if (p.size < 5) return null
            val seed = p[2].toLongOrNull() ?: return null
            val day = p[3].toLongOrNull() ?: return null
            if (p[0] != "0" && p[0] != "1") return null
            // A sixth field arrived in round 004; an older phone's five fields mean "not checked".
            return PilotSettings(p[0] == "1", p[1], seed, day, p[4] == "1", p.getOrNull(5) == "1")
        }
    }
}
