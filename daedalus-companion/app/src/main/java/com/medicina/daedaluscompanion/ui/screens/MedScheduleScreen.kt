package com.medicina.daedaluscompanion.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.data.LocalStore
import com.medicina.daedaluscompanion.data.MedReminders
import com.medicina.daedaluscompanion.data.ScheduledDose
import java.text.DateFormat
import java.util.Date

/** Profile → Medication schedule: the daily dose times that drive the Meds checklist and reminders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedScheduleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    var doses by remember { mutableStateOf(store.schedule()) }
    var showAdd by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun changed() {
        doses = store.schedule()
        MedReminders.reschedule(context)
    }

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        BackHeader(stringResource(R.string.schedule_title), onBack)
        Text(stringResource(R.string.schedule_lead), fontSize = 14.sp, color = DesignTokens.Muted)

        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            if (doses.isEmpty()) {
                Text(stringResource(R.string.schedule_empty), style = CaptionStyle, modifier = Modifier.padding(vertical = 16.dp))
            }
            doses.forEachIndexed { i, dose ->
                ListRow(
                    title = "${doseTime(dose)} · ${dose.name}",
                    subtitle = dose.dosage.ifBlank { null },
                    icon = Icons.Outlined.Schedule,
                    showDivider = i < doses.lastIndex,
                    trailing = {
                        IconButton(onClick = { store.removeScheduledDose(dose.id); changed() }) {
                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.schedule_delete_cd, dose.name), tint = DesignTokens.Muted)
                        }
                    },
                )
            }
        }

        PillButton(stringResource(R.string.schedule_add), onClick = { showAdd = true }, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth())
    }

    if (showAdd) {
        val time = rememberTimePickerState(8, 0, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
        var name by remember { mutableStateOf(doses.lastOrNull()?.name ?: "") }
        var dosage by remember { mutableStateOf(doses.lastOrNull()?.dosage ?: "") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = DesignTokens.Surface,
            shape = RoundedCornerShape(DesignTokens.RadiusXLarge),
            title = { Text(stringResource(R.string.schedule_add), style = SectionStyle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TimeInput(
                        state = time,
                        colors = TimePickerDefaults.colors(
                            timeSelectorSelectedContainerColor = DesignTokens.SageTint,
                            timeSelectorSelectedContentColor = DesignTokens.SageDeep,
                            periodSelectorSelectedContainerColor = DesignTokens.SageTint,
                            periodSelectorSelectedContentColor = DesignTokens.SageDeep,
                        ),
                    )
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.meds_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(dosage, { dosage = it }, label = { Text(stringResource(R.string.meds_dosage)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                PillButton(stringResource(R.string.meds_save), enabled = name.isNotBlank(), height = 44.dp, onClick = {
                    store.addScheduledDose(name.trim(), dosage.trim(), time.hour, time.minute)
                    changed()
                    showAdd = false
                    // Reminders need notification permission on Android 13+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel), color = DesignTokens.Muted) }
            },
        )
    }
}

/** "8:00" / "8 h 00" etc. in the device's time format. */
fun doseTime(dose: ScheduledDose): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(dose.timeOn(System.currentTimeMillis())))
