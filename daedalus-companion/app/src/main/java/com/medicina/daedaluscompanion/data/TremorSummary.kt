package com.medicina.daedaluscompanion.data

import android.content.Context
import com.medicina.daedaluscompanion.R
import java.io.File

/**
 * One line for a tremor check, used by the Cues card and the Journal:
 * "Rhythm 5.2 Hz · Moderate", "No clear tremor rhythm", "Too much movement" or "Check incomplete".
 * The frequency uses the app language's decimal separator ("5,2" in French).
 */
fun tremorSummary(context: Context, result: TremorAnalyzer.Result): String {
    val hz = result.frequencyHz
    return when {
        result.outcome == TremorAnalyzer.Outcome.TREMOR && hz != null -> {
            val locale = context.resources.configuration.locales[0]
            val strength = context.getString(
                when (result.strength) {
                    TremorAnalyzer.Strength.STRONG -> R.string.tremor_strength_strong
                    TremorAnalyzer.Strength.MODERATE -> R.string.tremor_strength_moderate
                    else -> R.string.tremor_strength_light
                }
            )
            context.getString(R.string.tremor_summary_rhythm, String.format(locale, "%.1f", hz), strength)
        }
        result.outcome == TremorAnalyzer.Outcome.MOVED -> context.getString(R.string.tremor_moved)
        result.outcome == TremorAnalyzer.Outcome.INCOMPLETE -> context.getString(R.string.tremor_incomplete)
        else -> context.getString(R.string.tremor_none)
    }
}

/** Reads and analyses a tremor session file. Call on Dispatchers.IO. */
fun analyzeTremorFile(file: File): TremorAnalyzer.Result =
    runCatching { TremorAnalyzer.analyzeCsv(file.readText()) }
        .getOrDefault(TremorAnalyzer.Result(TremorAnalyzer.Outcome.INCOMPLETE))
