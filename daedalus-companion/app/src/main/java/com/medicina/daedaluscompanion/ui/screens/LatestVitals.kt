package com.medicina.daedaluscompanion.ui.screens

import android.content.Context
import java.io.File

/** Most recent reading of each vital sign found in the watch session files, with when it was measured. */
data class LatestVitals(
    val heartRate: Int? = null,
    val heartRateAt: Long? = null,
    val breathing: Float? = null,
    val breathingAt: Long? = null,
    val spo2: Int? = null,
    val spo2At: Long? = null,
) {
    val complete get() = heartRate != null && breathing != null && spo2 != null
}

/**
 * Scans session files newest first (up to [maxFiles]) for the last valid heart rate,
 * breathing rate and SpO2. Call off the main thread.
 */
fun loadLatestVitals(context: Context, sessions: List<SessionItem>, maxFiles: Int = 10): LatestVitals {
    var result = LatestVitals()
    for (session in sessions.sortedByDescending { it.date }.take(maxFiles)) {
        if (result.complete) break
        val file = File(sessionsDir(context), session.fileName)
        var hr: Pair<Int, Long>? = null
        var br: Pair<Float, Long>? = null
        var sp: Pair<Int, Long>? = null
        runCatching {
            file.bufferedReader().useLines { lines ->
                var tCol = -1; var hrCol = -1; var brCol = -1; var spCol = -1
                lines.forEachIndexed { i, line ->
                    val cells = line.split(',')
                    if (i == 0) {
                        tCol = cells.indexOf("timestamp_ms"); hrCol = cells.indexOf("heart_rate_bpm")
                        brCol = cells.indexOf("breathing_rate_brpm"); spCol = cells.indexOf("spo2_percent")
                        return@forEachIndexed
                    }
                    val t = cells.getOrNull(tCol)?.toLongOrNull() ?: return@forEachIndexed
                    cells.getOrNull(hrCol)?.toFloatOrNull()?.takeIf { it in 30f..220f }?.let { hr = it.toInt() to t }
                    cells.getOrNull(brCol)?.toFloatOrNull()?.takeIf { it in 4f..40f }?.let { br = it to t }
                    cells.getOrNull(spCol)?.toIntOrNull()?.takeIf { it in 70..100 }?.let { sp = it to t }
                }
            }
        }
        // Files are newest first, so only fill what a newer file didn't have.
        result = result.copy(
            heartRate = result.heartRate ?: hr?.first, heartRateAt = result.heartRateAt ?: hr?.second,
            breathing = result.breathing ?: br?.first, breathingAt = result.breathingAt ?: br?.second,
            spo2 = result.spo2 ?: sp?.first, spo2At = result.spo2At ?: sp?.second,
        )
    }
    return result
}
