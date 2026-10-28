package com.medicina.daedaluswatch.haptics

import android.content.Context

/**
 * Pulse rate per cue mode. The rate is chosen on the phone (Cues screen) and sent to the
 * watch; the watch only stores it so "I'm stuck" starts at the person's own rhythm.
 */
object CueRates {
    private const val PREFS = "cue_rates"

    // "rest" is the breathing pacer: breaths per minute (6 = In 4 s · Out 6 s).
    private val defaults = mapOf("walking" to 40, "rest" to 6, "writing" to 90, "stress" to 60)

    fun get(context: Context, mode: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(mode, defaults[mode] ?: 80)

    fun set(context: Context, mode: String, bpm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(mode, bpm).apply()
    }
}
