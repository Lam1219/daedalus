package com.medicina.daedaluscompanion.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.data.Feeling
import com.medicina.daedaluscompanion.data.FeelingCheckIn
import com.medicina.daedaluscompanion.data.LocalStore
import com.medicina.daedaluscompanion.data.MedReminders
import com.medicina.daedaluscompanion.data.MedicationLog
import com.medicina.daedaluscompanion.data.ScheduledDose
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Meds tab. Without a schedule (Profile → Medication schedule) it is a simple dose log.
 * With a schedule it shows the designed view: today's dose checklist, reminders and
 * this week's adherence.
 */
@Composable
fun MedicationScreen() {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    var medications by remember { mutableStateOf(store.medications()) }
    val schedule = remember { store.schedule() }
    var lastCheckIn by remember { mutableStateOf(store.checkIns().firstOrNull()?.takeIf { isToday(it.timestamp) }) }
    var showAddDialog by remember { mutableStateOf(false) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    val doseLogged = stringResource(R.string.meds_logged)
    val doseDeleted = stringResource(R.string.meds_deleted)
    val checkInSaved = stringResource(R.string.feel_saved_short)

    /** Shows [message] with Undo for about 8 seconds; [undo] runs only if it's tapped. */
    fun offerUndo(message: String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(UNDO_MS) {
                snackbar.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }

    fun logDose(log: MedicationLog) {
        store.addMedication(log)
        medications = store.medications()
        offerUndo(doseLogged) { store.removeMedication(log); medications = store.medications() }
    }

    fun deleteDose(log: MedicationLog) {
        store.removeMedication(log)
        medications = store.medications()
        offerUndo(doseDeleted) { store.addMedication(log); medications = store.medications() }
    }

    Box(Modifier.fillMaxSize()) {
        ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.meds_title), style = TitleStyle)
                FilledIconButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = DesignTokens.SageDeep, contentColor = Color.White),
                ) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.meds_add_cd)) }
            }

            FeelingCard(lastCheckIn, timeFormat) { feeling ->
                val previous = lastCheckIn
                val c = FeelingCheckIn(feeling, System.currentTimeMillis())
                store.addCheckIn(c)
                lastCheckIn = c
                offerUndo(checkInSaved) { store.removeCheckIn(c); lastCheckIn = previous }
            }

            if (schedule.isEmpty()) {
                LoggedDoses(medications, timeFormat, onAdd = { showAddDialog = true }, onDelete = ::deleteDose)
            } else {
                ScheduledDoses(schedule, medications, timeFormat, onDelete = ::deleteDose) { dose ->
                    MedReminders.markTaken(context, dose)?.let { log ->
                        medications = store.medications()
                        offerUndo(doseLogged) { store.removeMedication(log); medications = store.medications() }
                    }
                }
                RemindersCard(store)
                WeekCard(schedule, medications)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
            Snackbar(
                data,
                shape = RoundedCornerShape(14.dp),
                containerColor = DesignTokens.Ink,
                contentColor = Color.White,
                actionColor = DesignTokens.SageTint,
            )
        }
    }

    if (showAddDialog) {
        AddDoseDialog(
            initialName = medications.firstOrNull()?.name ?: schedule.firstOrNull()?.name ?: "",
            initialDosage = medications.firstOrNull()?.dosage ?: schedule.firstOrNull()?.dosage ?: "",
            onDismiss = { showAddDialog = false },
            onSave = { name, dosage ->
                logDose(MedicationLog(name, dosage, System.currentTimeMillis()))
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun FeelingCard(lastCheckIn: FeelingCheckIn?, timeFormat: DateFormat, onPick: (Feeling) -> Unit) {
    SageCard(color = DesignTokens.SageTint, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Text(stringResource(R.string.feel_title), style = RowTitleStyle)
        Spacer(Modifier.height(12.dp))
        FlowRowCompat {
            listOf(Feeling.GOOD to R.string.feel_good, Feeling.WEARING_OFF to R.string.feel_wearing, Feeling.UNSURE to R.string.feel_unsure).forEach { (feeling, label) ->
                val on = lastCheckIn?.feeling == feeling
                Surface(
                    onClick = { onPick(feeling) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) DesignTokens.SageDeep else DesignTokens.Surface,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { selected = on },
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else DesignTokens.Ink)
                    }
                }
            }
        }
        lastCheckIn?.let {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.feel_saved, timeFormat.format(Date(it.timestamp))), fontSize = 12.sp, color = DesignTokens.Sage)
        }
    }
}

/** No schedule: today's logged doses, newest first (the original Meds screen). */
@Composable
private fun LoggedDoses(medications: List<MedicationLog>, timeFormat: DateFormat, onAdd: () -> Unit, onDelete: (MedicationLog) -> Unit) {
    val today = medications.filter { isToday(it.timestamp) }
    SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.meds_today), style = RowTitleStyle, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        if (today.isEmpty()) {
            Text(stringResource(R.string.meds_none), style = CaptionStyle, modifier = Modifier.padding(vertical = 12.dp))
        }
        today.forEachIndexed { i, med ->
            DoseRow(
                title = listOf(med.name, med.dosage).filter { it.isNotBlank() }.joinToString(" · "),
                status = stringResource(R.string.meds_taken_at, timeFormat.format(Date(med.timestamp))),
                state = DoseState.TAKEN,
                divider = i < today.lastIndex,
                onDelete = { onDelete(med) },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
    PillButton(stringResource(R.string.meds_add_cd), onClick = onAdd, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth())

    val weekStart = remember { startOfDay(System.currentTimeMillis()) - 6 * DAY_MS }
    val weekCount = medications.count { it.timestamp >= weekStart }
    SageCard(radius = DesignTokens.RadiusLarge, padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.meds_week), style = RowTitleStyle)
            Text(pluralStringResource(R.plurals.meds_week_count, weekCount, weekCount), fontSize = 13.sp, color = DesignTokens.Muted)
        }
    }
}

internal enum class DoseState { TAKEN, DUE, MISSED, UPCOMING }

/**
 * A scheduled dose's state at [now], for a dose time at [at]: "Due now" from its time until an
 * hour after, then "Missed". Same rule as the iPhone app.
 */
internal fun doseState(now: Long, at: Long, taken: Boolean): DoseState = when {
    taken -> DoseState.TAKEN
    now < at -> DoseState.UPCOMING
    now < at + DUE_FOR_MS -> DoseState.DUE
    else -> DoseState.MISSED
}

/** With a schedule: each dose time today as taken / due now / missed / upcoming, plus extra doses. */
@Composable
private fun ScheduledDoses(
    schedule: List<ScheduledDose>,
    medications: List<MedicationLog>,
    timeFormat: DateFormat,
    onDelete: (MedicationLog) -> Unit,
    onTake: (ScheduledDose) -> Unit,
) {
    val now = System.currentTimeMillis()
    val todayLogs = medications.filter { isToday(it.timestamp) }
    val extras = todayLogs.filter { it.slotId == null || schedule.none { d -> d.id == it.slotId } }
    SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.meds_today), style = RowTitleStyle, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        schedule.forEachIndexed { i, dose ->
            val taken = todayLogs.firstOrNull { it.slotId == dose.id }
            val at = dose.timeOn(now)
            val state = doseState(now, at, taken != null)
            DoseRow(
                title = listOf(doseTime(dose), dose.name, dose.dosage).filter { it.isNotBlank() }.joinToString(" · "),
                status = when (state) {
                    DoseState.TAKEN -> stringResource(R.string.meds_taken_at, timeFormat.format(Date(taken!!.timestamp)))
                    DoseState.DUE -> stringResource(R.string.meds_due_now)
                    DoseState.MISSED -> stringResource(R.string.meds_missed, doseTime(dose))
                    DoseState.UPCOMING -> stringResource(R.string.meds_upcoming)
                },
                state = state,
                // Take can be tapped from 30 minutes early, and late if a dose was missed.
                canTake = state != DoseState.TAKEN && now >= at - TAKE_EARLY_MS,
                divider = i < schedule.lastIndex || extras.isNotEmpty(),
                onTake = { onTake(dose) },
                onDelete = taken?.let { { onDelete(it) } },
            )
        }
        extras.forEachIndexed { i, med ->
            DoseRow(
                title = listOf(med.name, med.dosage).filter { it.isNotBlank() }.joinToString(" · "),
                status = stringResource(R.string.meds_extra_at, timeFormat.format(Date(med.timestamp))),
                state = DoseState.TAKEN,
                divider = i < extras.lastIndex,
                onDelete = { onDelete(med) },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DoseRow(
    title: String,
    status: String,
    state: DoseState,
    divider: Boolean,
    canTake: Boolean = false,
    onTake: () -> Unit = {},
    /** Removes a logged dose (taken by mistake); null when there's nothing to remove. */
    onDelete: (() -> Unit)? = null,
) {
    Column {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                DoseState.TAKEN -> Box(Modifier.size(32.dp).clip(CircleShape).background(DesignTokens.Sage), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                DoseState.DUE -> Box(Modifier.size(32.dp).border(2.dp, DesignTokens.Sage, CircleShape))
                DoseState.MISSED -> Box(Modifier.size(32.dp).border(2.dp, DesignTokens.Alert, CircleShape))
                DoseState.UPCOMING -> Box(Modifier.size(32.dp).border(2.dp, DesignTokens.Outline, CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
                Text(
                    status,
                    fontSize = 12.sp,
                    fontWeight = if (state == DoseState.DUE || state == DoseState.MISSED) FontWeight.SemiBold else FontWeight.Normal,
                    color = when (state) {
                        DoseState.DUE -> DesignTokens.Sage
                        DoseState.MISSED -> DesignTokens.Alert
                        else -> DesignTokens.Muted
                    },
                )
            }
            if (canTake) {
                PillButton(stringResource(R.string.meds_take), onClick = onTake, height = 48.dp)
            }
            if (state == DoseState.TAKEN && onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.meds_delete_cd, title), tint = DesignTokens.Muted)
                }
            }
        }
        if (divider) HorizontalDivider(color = DesignTokens.Divider)
    }
}

@Composable
private fun RemindersCard(store: LocalStore) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(store.getBoolean(LocalStore.MED_REMINDERS, true)) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    SageCard(radius = DesignTokens.RadiusLarge, padding = PaddingValues(horizontal = 20.dp, vertical = 2.dp)) {
        ListRow(
            title = stringResource(R.string.meds_reminders),
            subtitle = stringResource(R.string.meds_reminders_sub),
            icon = Icons.Outlined.NotificationsNone,
            trailing = {
                SageSwitch(on, { value ->
                    on = value
                    store.setBoolean(LocalStore.MED_REMINDERS, value)
                    MedReminders.reschedule(context)
                    if (value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            },
        )
    }
}

/** Scheduled doses taken over the last 7 days, out of those due so far (counted from when each was added). */
@Composable
private fun WeekCard(schedule: List<ScheduledDose>, medications: List<MedicationLog>) {
    val now = System.currentTimeMillis()
    var due = 0
    var taken = 0
    for (back in 0..6) {
        val day = now - back * DAY_MS
        schedule.forEach { dose ->
            val at = dose.timeOn(day)
            val wasTaken = medications.any { it.slotId == dose.id && LocalStore.sameDay(it.timestamp, day) }
            if ((at <= now && at >= dose.createdAt) || wasTaken) {
                due++
                if (wasTaken) taken++
            }
        }
    }
    SageCard(radius = DesignTokens.RadiusLarge, padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.meds_week), style = RowTitleStyle)
            Text(stringResource(R.string.meds_week_of, taken, due), fontSize = 13.sp, color = DesignTokens.Muted)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(DesignTokens.SageTrack)) {
            val share = if (due == 0) 0f else taken.toFloat() / due
            Box(Modifier.fillMaxWidth(share).height(8.dp).clip(RoundedCornerShape(4.dp)).background(DesignTokens.Sage))
        }
    }
}

@Composable
private fun AddDoseDialog(initialName: String, initialDosage: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var medName by remember { mutableStateOf(initialName) }
    var medDosage by remember { mutableStateOf(initialDosage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesignTokens.Surface,
        shape = RoundedCornerShape(DesignTokens.RadiusXLarge),
        title = { Text(stringResource(R.string.meds_add_cd), style = SectionStyle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(medName, { medName = it }, label = { Text(stringResource(R.string.meds_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors())
                OutlinedTextField(medDosage, { medDosage = it }, label = { Text(stringResource(R.string.meds_dosage)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors())
            }
        },
        confirmButton = {
            PillButton(stringResource(R.string.meds_save), enabled = medName.isNotBlank(), height = 44.dp, onClick = { onSave(medName.trim(), medDosage.trim()) })
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = DesignTokens.Muted) }
        },
    )
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
/** How long Undo stays up after logging or deleting. */
private const val UNDO_MS = 8_000L
/** A dose is "Due now" for an hour after its time, then "Missed". */
private const val DUE_FOR_MS = 60 * 60 * 1000L
/** Take shows from 30 minutes before a dose's time. */
private const val TAKE_EARLY_MS = 30 * 60 * 1000L

private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Wrapping row without the experimental FlowRow API. */
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0; var y = 0; var rowH = 0
        val positions = placeables.map { p ->
            if (x > 0 && x + p.width > constraints.maxWidth) { x = 0; y += rowH + gap; rowH = 0 }
            val pos = x to y
            x += p.width + gap; rowH = maxOf(rowH, p.height)
            pos
        }
        layout(constraints.maxWidth, y + rowH) {
            placeables.forEachIndexed { i, p -> p.place(positions[i].first, positions[i].second) }
        }
    }
}
