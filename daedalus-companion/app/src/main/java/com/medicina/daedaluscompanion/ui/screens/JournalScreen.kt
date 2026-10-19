package com.medicina.daedaluscompanion.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.data.Feeling
import com.medicina.daedaluscompanion.data.LocalStore
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class JournalEntry(val time: Long, val title: String, val subtitle: String, val dot: Color)

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

@Composable
fun JournalScreen() {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    val days = remember {
        (6 downTo 0).map { back -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }.timeInMillis }
    }
    var selectedDay by remember { mutableStateOf(days.last()) }

    val sessions = remember { loadSessions(context) }
    val doses = remember { store.medications() }
    val checkIns = remember { store.checkIns() }

    // The day's heart rate from the session files; null while loading.
    var hrPoints by remember { mutableStateOf<List<HrPoint>?>(null) }
    LaunchedEffect(selectedDay) {
        hrPoints = null
        hrPoints = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadDayHeartRate(context, selectedDay, sessions)
        }
    }

    val doseTitle = stringResource(R.string.journal_dose)
    val sessionTitle = stringResource(R.string.journal_session)
    val feelGood = stringResource(R.string.feel_good)
    val feelWearing = stringResource(R.string.feel_wearing)
    val feelUnsure = stringResource(R.string.feel_unsure)
    val checkInLabel = stringResource(R.string.journal_checkin)
    val modeLabels = HAPTIC_MODES.associate { it.id to stringResource(it.labelRes) }
    val otherLabel = stringResource(R.string.mode_other)

    val entries = buildList {
        doses.filter { sameDay(it.timestamp, selectedDay) }.forEach {
            add(JournalEntry(it.timestamp, doseTitle, listOf(it.name, it.dosage).filter { s -> s.isNotBlank() }.joinToString(" · "), DesignTokens.Sage))
        }
        checkIns.filter { sameDay(it.timestamp, selectedDay) }.forEach {
            val label = when (it.feeling) { Feeling.GOOD -> feelGood; Feeling.WEARING_OFF -> feelWearing; Feeling.UNSURE -> feelUnsure }
            add(JournalEntry(it.timestamp, label, checkInLabel, if (it.feeling == Feeling.WEARING_OFF) DesignTokens.Alert else DesignTokens.SageLine))
        }
        sessions.filter { sameDay(it.date, selectedDay) }.forEach {
            add(JournalEntry(it.date, String.format(sessionTitle, modeLabels[it.mode] ?: otherLabel), formatFileSize(it.size), DesignTokens.SageDeep))
        }
    }.sortedBy { it.time }

    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dayLetter = remember { SimpleDateFormat("EEEEE", Locale.getDefault()) }
    val dayNumber = remember { SimpleDateFormat("d", Locale.getDefault()) }
    val longDate = remember { DateFormat.getDateInstance(DateFormat.LONG) }

    val shareSubject = stringResource(R.string.journal_share_subject, longDate.format(Date(selectedDay)))
    val shareChooser = stringResource(R.string.journal_share_chooser)
    val emptyText = stringResource(R.string.journal_empty)

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.journal_title), style = TitleStyle)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                val on = sameDay(day, selectedDay)
                Surface(
                    onClick = { selectedDay = day },
                    shape = RoundedCornerShape(16.dp),
                    color = if (on) DesignTokens.SageDeep else DesignTokens.Surface,
                    modifier = Modifier.weight(1f).height(56.dp).semantics { role = Role.Tab; selected = on },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(dayLetter.format(Date(day)).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else DesignTokens.Muted)
                        Text(dayNumber.format(Date(day)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else DesignTokens.Ink)
                    }
                }
            }
        }

        HeartRateCard(hrPoints, doses.filter { sameDay(it.timestamp, selectedDay) }.map { it.timestamp })

        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            if (entries.isEmpty()) {
                Text(emptyText, style = CaptionStyle, modifier = Modifier.padding(vertical = 18.dp))
            }
            entries.forEach { e ->
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(timeFormat.format(Date(e.time)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Muted, modifier = Modifier.width(64.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(e.dot))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
                        if (e.subtitle.isNotBlank()) Text(e.subtitle, style = CaptionStyle)
                    }
                }
            }
        }

        PillButton(
            text = stringResource(R.string.journal_share),
            icon = Icons.Outlined.Share,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val body = if (entries.isEmpty()) emptyText else entries.joinToString("\n") { "${timeFormat.format(Date(it.time))}  ${it.title}  ${it.subtitle}".trim() }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                    putExtra(Intent.EXTRA_TEXT, "$shareSubject\n\n$body")
                }
                context.startActivity(Intent.createChooser(intent, shareChooser))
            },
        )
    }
}
