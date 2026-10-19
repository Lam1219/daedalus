package com.medicina.daedaluscompanion.ui.screens

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicina.daedaluscompanion.R
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** One minute of heart rate: average of the watch samples in that minute. */
data class HrPoint(val timeMs: Long, val bpm: Float)

/**
 * Heart rate for one day, read from the session files the watch sent (heart_rate_bpm column,
 * zeros skipped), averaged per minute. Call off the main thread: files can be a few MB.
 */
fun loadDayHeartRate(context: Context, day: Long, sessions: List<SessionItem>): List<HrPoint> {
    val start = Calendar.getInstance().apply {
        timeInMillis = day; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val end = start + 24 * 60 * 60 * 1000L
    val perMinute = sortedMapOf<Long, Pair<Double, Int>>()
    // A session that started the day before can still run past midnight.
    sessions.filter { it.date in (start - 6 * 60 * 60 * 1000L) until end }.forEach { session ->
        val file = File(sessionsDir(context), session.fileName)
        runCatching {
            file.bufferedReader().useLines { lines ->
                var tCol = -1
                var hrCol = -1
                lines.forEachIndexed { i, line ->
                    val cells = line.split(',')
                    if (i == 0) {
                        tCol = cells.indexOf("timestamp_ms"); hrCol = cells.indexOf("heart_rate_bpm")
                        return@forEachIndexed
                    }
                    if (tCol < 0 || hrCol < 0) return@forEachIndexed
                    val t = cells.getOrNull(tCol)?.toLongOrNull() ?: return@forEachIndexed
                    val hr = cells.getOrNull(hrCol)?.toFloatOrNull() ?: return@forEachIndexed
                    if (t !in start until end || hr < 30f || hr > 220f) return@forEachIndexed
                    val minute = t / 60_000 * 60_000
                    val (sum, n) = perMinute[minute] ?: (0.0 to 0)
                    perMinute[minute] = (sum + hr) to (n + 1)
                }
            }
        }
    }
    return perMinute.map { (minute, acc) -> HrPoint(minute + 30_000, (acc.first / acc.second).toFloat()) }
}

/** Card with the day's heart-rate line and dashed lines at dose times (design "Phone · Journal"). */
@Composable
fun HeartRateCard(points: List<HrPoint>?, doseTimes: List<Long>) {
    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        val title = stringResource(R.string.journal_hr_title)
        if (points.isNullOrEmpty()) {
            Text(title, style = RowTitleStyle)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(if (points == null) R.string.journal_hr_loading else R.string.journal_hr_empty), style = CaptionStyle)
            return@SageCard
        }
        val lo = points.minOf { it.bpm }
        val hi = points.maxOf { it.bpm }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(title, style = RowTitleStyle)
            Text(stringResource(R.string.journal_hr_range, lo.toInt(), hi.toInt()), style = CaptionStyle)
        }
        Spacer(Modifier.height(10.dp))

        // Time axis: whole hours around the data and any doses, at least 2 h wide.
        val hour = 60 * 60 * 1000L
        val first = minOf(points.first().timeMs, doseTimes.minOrNull() ?: Long.MAX_VALUE)
        val last = maxOf(points.last().timeMs, doseTimes.maxOrNull() ?: Long.MIN_VALUE)
        val t0 = floorHour(first)
        val t1 = maxOf(floorHour(last) + hour, t0 + 2 * hour)
        val yLo = lo - 5f
        val yHi = hi + 5f

        val description = stringResource(
            R.string.journal_hr_chart_cd,
            timeFormat.format(Date(t0)), timeFormat.format(Date(t1)), lo.toInt(), hi.toInt(),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .semantics { contentDescription = description },
        ) {
            fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
            fun y(bpm: Float) = size.height - (bpm - yLo) / (yHi - yLo) * size.height

            // Quiet grid at three levels.
            listOf(0f, 0.5f, 1f).forEach { f ->
                val gy = size.height * f
                drawLine(DesignTokens.SageTrack, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
            }
            // Doses.
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            doseTimes.filter { it in t0..t1 }.forEach { t ->
                drawLine(DesignTokens.SageLine, Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }
            // Heart-rate line; a gap of more than 5 minutes (no session) breaks it.
            val path = Path()
            var prev: HrPoint? = null
            points.forEach { p ->
                if (prev == null || p.timeMs - prev!!.timeMs > 5 * 60_000) path.moveTo(x(p.timeMs), y(p.bpm))
                else path.lineTo(x(p.timeMs), y(p.bpm))
                prev = p
            }
            drawPath(path, DesignTokens.Sage, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Single-minute stretches would be invisible as a line: show them as dots.
            points.forEachIndexed { i, p ->
                val before = points.getOrNull(i - 1)?.let { p.timeMs - it.timeMs <= 5 * 60_000 } ?: false
                val after = points.getOrNull(i + 1)?.let { it.timeMs - p.timeMs <= 5 * 60_000 } ?: false
                if (!before && !after) drawCircle(DesignTokens.Sage, 2.5.dp.toPx(), Offset(x(p.timeMs), y(p.bpm)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..3).forEach { i -> Text(timeFormat.format(Date(t0 + (t1 - t0) * i / 3)), style = CaptionStyle) }
        }
        if (doseTimes.any { it in t0..t1 }) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.width(12.dp).height(10.dp)) {
                    drawLine(
                        DesignTokens.SageLine, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                    )
                }
                Text(stringResource(R.string.journal_hr_dose), style = CaptionStyle)
            }
        }
    }
}

private fun floorHour(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
