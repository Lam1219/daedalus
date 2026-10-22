package com.medicina.daedaluscompanion.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.auth.AuthRepository
import com.medicina.daedaluscompanion.data.LocalStore
import com.medicina.daedaluscompanion.data.Lifestyle
import com.medicina.daedaluscompanion.data.MedReminders
import com.medicina.daedaluscompanion.data.PersonalProfile
import com.medicina.daedaluscompanion.data.CloudBackup
import com.medicina.daedaluscompanion.data.Sex
import com.medicina.daedaluscompanion.data.Symptom
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle

/**
 * Shows [content] once the signed-in account has its personal information; otherwise runs the
 * setup first. A profile already saved in Supabase (e.g. from another phone) is reused.
 */
@Composable
fun ProfileSetupGate(auth: AuthRepository, email: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    var ready by remember(email) { mutableStateOf(store.personalProfile(email) != null) }
    var checkedServer by remember(email) { mutableStateOf(ready) }

    LaunchedEffect(email) {
        if (ready) return@LaunchedEffect
        val remote = auth.fetchProfile()
        if (remote is AuthRepository.RemoteProfile.Found) {
            store.savePersonalProfile(email, PersonalProfile.fromJson(remote.json), uploaded = true)
            ready = true
        }
        checkedServer = true
    }

    // Back up anything recorded before sign-in or while offline (profile, doses, sessions…).
    LaunchedEffect(email, ready) { if (ready) CloudBackup.syncAsync(context) }

    when {
        ready -> content()
        !checkedServer -> Box(Modifier.fillMaxSize().background(DesignTokens.Ground), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = DesignTokens.Sage)
        }
        else -> ProfileSetupScreen(auth, email, onDone = { ready = true })
    }
}

private enum class SetupStep { NAME, BIRTH, SEX, LIFESTYLE, MEDS, SYMPTOMS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSetupScreen(auth: AuthRepository, email: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableStateOf(SetupStep.NAME) }
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf<Sex?>(null) }
    var lifestyle by rememberSaveable { mutableStateOf<Lifestyle?>(null) }
    var symptoms by rememberSaveable { mutableStateOf(setOf<Symptom>()) }
    var doses by remember { mutableStateOf(store.schedule()) }
    var showAddDose by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var birthDay by rememberSaveable { mutableStateOf("") }
    var birthMonth by rememberSaveable { mutableStateOf<Int?>(null) }
    var birthYear by rememberSaveable { mutableStateOf("") }
    val birthDate = remember(birthDay, birthMonth, birthYear) { parseBirthDate(birthDay, birthMonth, birthYear) }
    val birthComplete = birthDay.isNotEmpty() && birthMonth != null && birthYear.length == 4

    val steps = SetupStep.entries
    fun next() { steps.getOrNull(step.ordinal + 1)?.let { step = it } }
    fun back() { steps.getOrNull(step.ordinal - 1)?.let { step = it } }

    fun finish() {
        val profile = PersonalProfile(firstName.trim(), lastName.trim(), birthDate?.toString(), sex, lifestyle, symptoms)
        saving = true
        scope.launch {
            // Saved on the phone right away; if Supabase can't be reached it's sent on a later start.
            val uploaded = store.cloudBackup && auth.saveProfile(profile.toJson())
            store.savePersonalProfile(email, profile, uploaded)
            saving = false
            onDone()
        }
    }

    BackHandler(enabled = step != SetupStep.NAME) { back() }

    AuthFrame(
        onBack = { back() },
        showBack = step != SetupStep.NAME,
        notConfigured = false,
        busy = saving,
        progress = (step.ordinal + 1f) / steps.size,
    ) {
        when (step) {
            SetupStep.NAME -> {
                Headline(R.string.setup_name_title, R.string.setup_name_sub)
                SetupTextField(firstName, { firstName = it }, R.string.setup_first_name)
                SetupTextField(lastName, { lastName = it }, R.string.setup_last_name)
                Spacer(Modifier.weight(1f))
                PrimaryButton(R.string.login_continue, enabled = firstName.isNotBlank()) { next() }
            }

            SetupStep.BIRTH -> {
                Headline(R.string.setup_birth_title, R.string.setup_birth_sub)
                BirthDateFields(
                    day = birthDay, onDay = { birthDay = it.filter(Char::isDigit).take(2) },
                    month = birthMonth, onMonth = { birthMonth = it },
                    year = birthYear, onYear = { birthYear = it.filter(Char::isDigit).take(4) },
                )
                if (birthComplete && birthDate == null) {
                    Text(stringResource(R.string.setup_birth_invalid), fontSize = 14.sp, color = DesignTokens.Alert)
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(R.string.login_continue, enabled = birthDate != null) { next() }
                SkipButton { birthDay = ""; birthMonth = null; birthYear = ""; next() }
            }

            SetupStep.SEX -> {
                Headline(R.string.setup_sex_title, R.string.setup_sex_sub)
                listOf(
                    Sex.FEMALE to R.string.setup_sex_female,
                    Sex.MALE to R.string.setup_sex_male,
                    Sex.INTERSEX to R.string.setup_sex_intersex,
                    Sex.UNDISCLOSED to R.string.setup_sex_undisclosed,
                ).forEach { (value, label) ->
                    ChoiceCard(label, null, selected = sex == value) { sex = value }
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(R.string.login_continue, enabled = sex != null) { next() }
            }

            SetupStep.LIFESTYLE -> {
                Headline(R.string.setup_life_title, R.string.setup_life_sub)
                listOf(
                    Triple(Lifestyle.SEDENTARY, R.string.setup_life_sedentary, R.string.setup_life_sedentary_sub),
                    Triple(Lifestyle.LIGHT, R.string.setup_life_light, R.string.setup_life_light_sub),
                    Triple(Lifestyle.ACTIVE, R.string.setup_life_active, R.string.setup_life_active_sub),
                    Triple(Lifestyle.VERY_ACTIVE, R.string.setup_life_very, R.string.setup_life_very_sub),
                ).forEach { (value, label, sub) ->
                    ChoiceCard(label, sub, selected = lifestyle == value) { lifestyle = value }
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(R.string.login_continue, enabled = lifestyle != null) { next() }
                SkipButton { lifestyle = null; next() }
            }

            SetupStep.MEDS -> {
                Headline(R.string.setup_meds_title, R.string.setup_meds_sub)
                if (doses.isNotEmpty()) {
                    SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                        doses.forEachIndexed { i, dose ->
                            ListRow(
                                title = "${doseTime(dose)} · ${dose.name}",
                                subtitle = dose.dosage.ifBlank { null },
                                icon = Icons.Outlined.Schedule,
                                showDivider = i < doses.lastIndex,
                                trailing = {
                                    IconButton(onClick = {
                                        store.removeScheduledDose(dose.id); doses = store.schedule(); MedReminders.reschedule(context)
                                    }) {
                                        Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.schedule_delete_cd, dose.name), tint = DesignTokens.Muted)
                                    }
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { showAddDose = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(1.dp, DesignTokens.SageLine),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = DesignTokens.Surface, contentColor = DesignTokens.SageDeep),
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.schedule_add), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(if (doses.isEmpty()) R.string.setup_meds_none else R.string.login_continue, enabled = true) { next() }
            }

            SetupStep.SYMPTOMS -> {
                Headline(R.string.setup_symptoms_title, R.string.setup_symptoms_sub)
                SageCard(padding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    symptomLabels.forEach { (symptom, label) ->
                        CheckRow(label, checked = symptom in symptoms) {
                            symptoms = if (symptom in symptoms) symptoms - symptom else symptoms + symptom
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(R.string.setup_finish, enabled = !saving) { finish() }
            }
        }
    }

    if (showAddDose) {
        AddDoseDialog(
            initialName = doses.lastOrNull()?.name ?: "",
            initialDosage = doses.lastOrNull()?.dosage ?: "",
            onDismiss = { showAddDose = false },
            onSave = { name, dosage, hour, minute ->
                store.addScheduledDose(name, dosage, hour, minute)
                doses = store.schedule()
                MedReminders.reschedule(context)
                showAddDose = false
            },
        )
    }
}

private val symptomLabels = listOf(
    Symptom.TREMOR to R.string.symptom_tremor,
    Symptom.SLOWNESS to R.string.symptom_slowness,
    Symptom.STIFFNESS to R.string.symptom_stiffness,
    Symptom.FREEZING to R.string.symptom_freezing,
    Symptom.BALANCE to R.string.symptom_balance,
    Symptom.DYSKINESIA to R.string.symptom_dyskinesia,
    Symptom.WEARING_OFF to R.string.symptom_wearing_off,
    Symptom.HANDWRITING to R.string.symptom_handwriting,
    Symptom.VOICE to R.string.symptom_voice,
    Symptom.SWALLOWING to R.string.symptom_swallowing,
    Symptom.SLEEP to R.string.symptom_sleep,
    Symptom.FATIGUE to R.string.symptom_fatigue,
    Symptom.MOOD to R.string.symptom_mood,
    Symptom.MEMORY to R.string.symptom_memory,
    Symptom.CONSTIPATION to R.string.symptom_constipation,
    Symptom.SMELL to R.string.symptom_smell,
    Symptom.DIZZINESS to R.string.symptom_dizziness,
    Symptom.PAIN to R.string.symptom_pain,
)

/** A real date between 1900 and today, or null. */
private fun parseBirthDate(day: String, month: Int?, year: String): LocalDate? {
    val d = day.toIntOrNull() ?: return null
    val y = year.toIntOrNull() ?: return null
    if (month == null || y < 1900) return null
    val date = runCatching { LocalDate.of(y, month, d) }.getOrNull() ?: return null
    return date.takeUnless { it.isAfter(LocalDate.now()) }
}

/** Day / month (by name) / year: clearer than a calendar or a typed date pattern. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDateFields(
    day: String, onDay: (String) -> Unit,
    month: Int?, onMonth: (Int) -> Unit,
    year: String, onYear: (String) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val months = remember(locale) { Month.entries.map { it.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { c -> c.uppercase(locale) } } }
    var expanded by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = day, onValueChange = onDay,
            label = { Text(stringResource(R.string.setup_birth_day)) },
            placeholder = { Text("15") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            modifier = Modifier.weight(0.8f),
        )
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1.5f)) {
            OutlinedTextField(
                value = month?.let { months[it - 1] } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.setup_birth_month)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = DesignTokens.Surface) {
                months.forEachIndexed { i, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onMonth(i + 1); expanded = false })
                }
            }
        }
        OutlinedTextField(
            value = year, onValueChange = onYear,
            label = { Text(stringResource(R.string.setup_birth_year)) },
            placeholder = { Text("1955") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            modifier = Modifier.weight(1.1f),
        )
    }
}

@Composable
private fun SetupTextField(value: String, onChange: (String) -> Unit, @StringRes label: Int) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(16.dp), colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A single-choice option: white card, sage border and check when selected. */
@Composable
private fun ChoiceCard(@StringRes label: Int, @StringRes sub: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) DesignTokens.SageTint else DesignTokens.Surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) DesignTokens.SageDeep else DesignTokens.SwitchOff, RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(label), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
            if (sub != null) Text(stringResource(sub), fontSize = 13.sp, color = DesignTokens.Muted)
        }
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) DesignTokens.SageDeep else DesignTokens.Surface)
                .border(1.5.dp, if (selected) DesignTokens.SageDeep else DesignTokens.SageLine, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Outlined.Check, null, tint = DesignTokens.Surface, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun CheckRow(@StringRes label: Int, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = DesignTokens.SageDeep, uncheckedColor = DesignTokens.SageLine),
            modifier = Modifier.padding(12.dp),
        )
        Text(stringResource(label), fontSize = 15.sp, color = DesignTokens.Ink)
    }
}

@Composable
private fun ColumnScope.SkipButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(R.string.setup_skip), color = DesignTokens.Muted, fontSize = 14.sp)
    }
}
