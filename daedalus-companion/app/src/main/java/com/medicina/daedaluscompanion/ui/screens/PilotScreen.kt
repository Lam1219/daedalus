package com.medicina.daedaluscompanion.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.pilot.PilotFiles
import com.medicina.daedaluscompanion.pilot.PilotPhoneService
import com.medicina.daedaluscompanion.pilot.PilotPrefs
import com.medicina.daedaluscompanion.utils.DaedalusApplication
import com.medicina.shared.pilot.PilotSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Profile → Home pilot. The pilot's settings (study switch, code, seed, enrolment date, pocket
 * reminder), sent to the watch on every change, and "Export pilot files". With study mode off the
 * apps behave as without the pilot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PilotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = (context as? Activity)?.application as? DaedalusApplication ?: return
    val scope = rememberCoroutineScope()

    var settings by remember {
        mutableStateOf(PilotPrefs.settings(context).let { if (it.enrolmentEpochDay == 0L) it.copy(enrolmentEpochDay = LocalDate.now().toEpochDay()) else it })
    }
    var code by remember { mutableStateOf(settings.participant) }
    var seedText by remember { mutableStateOf(if (settings.participant.isEmpty()) "" else settings.seed.toString()) }
    var sendResult by remember { mutableStateOf<Int?>(null) }
    var pressCount by remember { mutableIntStateOf(0) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var needActivity by remember { mutableStateOf(false) }

    val codeOk = PilotSettings.isValidCode(code)
    val seed = seedText.toLongOrNull()
    val seedOk = seed != null && PilotSettings.isValidSeed(seed)
    val locked = settings.enabled

    LaunchedEffect(Unit) { pressCount = withContext(Dispatchers.IO) { PilotFiles.pressCount(context) } }

    fun apply(s: PilotSettings) {
        settings = s
        PilotPrefs.save(context, s)
        PilotPhoneService.refresh(context)
        scope.launch {
            val sent = runCatching { app.watchClient.sendPilotSettings(s) }.getOrDefault(false)
            sendResult = if (sent) R.string.pilot_sent else R.string.pilot_not_sent
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        needActivity = granted[Manifest.permission.ACTIVITY_RECOGNITION] == false
        // The phone's part needs "physical activity" (steps, and the foreground service on Android 14+).
        apply(settings.copy(enabled = true, participant = code, seed = seed ?: 0))
    }

    fun turnOn() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) apply(settings.copy(enabled = true, participant = code, seed = seed ?: 0))
        else permissions.launch(wanted.toTypedArray())
    }

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        BackHeader(stringResource(R.string.pilot_title), onBack)
        Text(stringResource(R.string.pilot_intro), fontSize = 15.sp, color = DesignTokens.Ink)

        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            ListRow(
                title = stringResource(R.string.pilot_switch),
                subtitle = stringResource(R.string.pilot_switch_sub),
                showDivider = true,
                trailing = {
                    SageSwitch(
                        checked = settings.enabled,
                        enabled = settings.enabled || (codeOk && seedOk),
                        onCheckedChange = { on -> if (on) turnOn() else apply(settings.copy(enabled = false)) },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                code, { code = it.trim().uppercase() },
                label = { Text(stringResource(R.string.pilot_code)) },
                singleLine = true, enabled = !locked,
                isError = code.isNotEmpty() && !codeOk,
                supportingText = { if (code.isNotEmpty() && !codeOk) Text(stringResource(R.string.pilot_code_error)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors(),
            )
            OutlinedTextField(
                seedText, { seedText = it.filter(Char::isDigit).take(10) },
                label = { Text(stringResource(R.string.pilot_seed)) },
                singleLine = true, enabled = !locked,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = seedText.isNotEmpty() && !seedOk,
                supportingText = { if (seedText.isNotEmpty() && !seedOk) Text(stringResource(R.string.pilot_seed_error)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors(),
            )
            ListRow(
                title = stringResource(R.string.pilot_enrolment),
                subtitle = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(LocalDate.ofEpochDay(settings.enrolmentEpochDay)),
                icon = Icons.Outlined.DateRange,
                showDivider = true,
                onClick = if (locked) null else ({ pickDate = true }),
            )
            if (locked) Text(stringResource(R.string.pilot_locked), style = CaptionStyle, modifier = Modifier.padding(vertical = 8.dp))
            ListRow(
                title = stringResource(R.string.pilot_pocket),
                subtitle = stringResource(R.string.pilot_pocket_sub),
                trailing = { SageSwitch(settings.pocketReminder, { apply(settings.copy(pocketReminder = it)) }) },
            )
        }
        sendResult?.let {
            Text(stringResource(it), fontSize = 14.sp, color = DesignTokens.Ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (needActivity) Text(stringResource(R.string.pilot_activity_needed), fontSize = 14.sp, color = DesignTokens.Alert)

        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            Text(stringResource(R.string.pilot_files), style = RowTitleStyle)
            Text(pluralStringResource(R.plurals.pilot_presses_count, pressCount, pressCount), fontSize = 15.sp, color = DesignTokens.Ink)
            Text(stringResource(R.string.pilot_files_note), style = CaptionStyle)
            Spacer(Modifier.height(12.dp))
            PillButton(
                stringResource(R.string.pilot_export),
                icon = Icons.Outlined.IosShare,
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
                onClick = { scope.launch { exportPilotFiles(context, settings.participant) } },
            )
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.pilot_delete), color = DesignTokens.Alert, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = settings.enrolmentEpochDay * DAY_MS)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val day = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        settings = settings.copy(enrolmentEpochDay = day)
                        PilotPrefs.save(context, settings)
                    }
                    pickDate = false
                }) { Text(stringResource(R.string.meds_save), color = DesignTokens.SageDeep) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel), color = DesignTokens.Muted) } },
        ) { DatePicker(state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = DesignTokens.Surface,
            title = { Text(stringResource(R.string.pilot_delete), style = SectionStyle) },
            text = { Text(stringResource(R.string.pilot_delete_confirm), fontSize = 15.sp, color = DesignTokens.Ink) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { PilotFiles.deleteAll(context) }
                        pressCount = 0
                    }
                }) { Text(stringResource(R.string.delete), color = DesignTokens.Alert, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel), color = DesignTokens.Muted) } },
        )
    }
}

/** Profile row subtitle: "Off", or "On · P03". */
@Composable
fun pilotRowSubtitle(context: Context): String {
    val s = remember { PilotPrefs.settings(context) }
    return if (s.active) stringResource(R.string.pilot_row_on, s.participant) else stringResource(R.string.pilot_row_off)
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Zips every pilot file and opens the share sheet (email, Drive, a cable to a computer…). */
private suspend fun exportPilotFiles(context: Context, participant: String) {
    val zip = withContext(Dispatchers.IO) { PilotFiles.zip(context, participant) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.pilot_export_chooser)))
}
