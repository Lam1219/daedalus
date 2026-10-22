package com.medicina.daedaluscompanion.ui.screens

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.wearable.Wearable
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.auth.AuthState
import com.medicina.daedaluscompanion.ble.ConnectionState
import com.medicina.daedaluscompanion.data.LocalStore
import com.medicina.daedaluscompanion.utils.DaedalusApplication
import com.medicina.daedaluscompanion.wear.SessionState
import com.medicina.daedaluscompanion.wear.await
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Cue modes the watch understands. `id` is the wire value sent over /session/control. */
data class HapticMode(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val icon: ImageVector,
    val bpm: Int,
)

val HAPTIC_MODES = listOf(
    HapticMode("walking", R.string.mode_walk, R.string.mode_walk_desc, Icons.AutoMirrored.Outlined.DirectionsWalk, 80),
    HapticMode("rest", R.string.mode_rest, R.string.mode_rest_desc, Icons.Outlined.Air, 6), // breaths per minute (pacer)
    HapticMode("writing", R.string.mode_fine, R.string.mode_fine_desc, Icons.Outlined.Edit, 90),
    HapticMode("stress", R.string.mode_calm, R.string.mode_calm_desc, Icons.Outlined.Waves, 60),
)

fun modeById(id: String?): HapticMode? = HAPTIC_MODES.firstOrNull { it.id == id }

fun isToday(timestamp: Long): Boolean {
    val a = Calendar.getInstance()
    val b = Calendar.getInstance().apply { timeInMillis = timestamp }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

@Composable
fun HomeScreen(onOpenCues: () -> Unit, onOpenMeds: () -> Unit, onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val app = (context as? Activity)?.application as? DaedalusApplication
    val store = remember { LocalStore(context) }

    var isSessionActive by remember { mutableStateOf(SessionState.isSessionActive) }
    var activeMode by remember { mutableStateOf(SessionState.getActiveSessionMode()) }
    var watchConnected by remember { mutableStateOf<Boolean?>(null) }
    var sessionsToday by remember { mutableStateOf(0) }
    var latest by remember { mutableStateOf(LatestVitals()) }

    LaunchedEffect(Unit) {
        var seenFiles = -1
        while (true) {
            isSessionActive = SessionState.isSessionActive
            activeMode = SessionState.getActiveSessionMode()
            val sessions = loadSessions(context)
            sessionsToday = sessions.count { isToday(it.date) }
            // Re-read vitals when a new session file arrives from the watch.
            val fingerprint = sessions.sumOf { it.size.toInt() } + sessions.size
            if (fingerprint != seenFiles) {
                seenFiles = fingerprint
                latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadLatestVitals(context, sessions) }
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    LaunchedEffect(Unit) {
        watchConnected = try {
            Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty()
        } catch (e: Exception) {
            android.util.Log.e("HomeScreen", "Connection check failed: ${e.message}")
            false
        }
    }

    val doses = remember { store.medications() }
    val dosesToday = doses.filter { isToday(it.timestamp) }
    val checkInsToday = remember { store.checkIns().count { isToday(it.timestamp) } }

    val strapState = app?.chestStrapBLE?.connectionState?.collectAsStateWithLifecycle()
    val samples = app?.chestStrapBLE?.ecgSamples?.collectAsStateWithLifecycle()
    // A connected chest strap gives live heart rate; otherwise the latest watch reading.
    val liveHr = if (strapState?.value == ConnectionState.CONNECTED) {
        samples?.value?.lastOrNull { it.hrBpm != null }?.hrBpm?.toInt()?.takeIf { it > 0 }
    } else null

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greetingBase = stringResource(
        when {
            hour < 12 -> R.string.greeting_morning
            hour < 18 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        }
    )
    // "Good evening, Marie" once the signed-in account has a first name.
    val authState = app?.auth?.state?.collectAsState()?.value
    val firstName = remember(authState) {
        (authState as? AuthState.SignedIn)?.let { store.personalProfile(it.email)?.firstName }?.ifBlank { null }
    }
    val greeting = if (firstName != null) stringResource(R.string.greeting_with_name, greetingBase, firstName) else greetingBase
    val dateLabel = remember { DateFormat.getDateInstance(DateFormat.FULL).format(Date()) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(dateLabel.replaceFirstChar { it.uppercase() }, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DesignTokens.Muted)
            Text(greeting, style = TitleStyle)
        }

        // Watch status
        val mode = modeById(activeMode)
        SageCard(color = DesignTokens.SageTint, radius = DesignTokens.RadiusLarge, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), onClick = onOpenProfile) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Outlined.Watch, size = 40.dp, background = DesignTokens.Surface)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            when (watchConnected) {
                                null -> R.string.watch_checking
                                true -> R.string.watch_connected
                                false -> R.string.watch_not_connected
                            }
                        ),
                        style = RowTitleStyle,
                    )
                    Text(
                        when {
                            watchConnected == false -> stringResource(R.string.watch_sub_pair)
                            isSessionActive && mode != null -> stringResource(R.string.watch_sub_cue_on, stringResource(mode.labelRes))
                            else -> stringResource(R.string.watch_sub_ready)
                        },
                        fontSize = 13.sp, color = DesignTokens.Sage,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = DesignTokens.Sage)
            }
        }

        // Today summary (real counts only)
        SageCard(onClick = onOpenCues) {
            Text(stringResource(R.string.today_title), style = SectionStyle)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryStat(dosesToday.size.toString(), R.string.today_doses, Modifier.weight(1f))
                SummaryStat(sessionsToday.toString(), R.string.today_sessions, Modifier.weight(1f))
                SummaryStat(checkInsToday.toString(), R.string.today_checkins, Modifier.weight(1f))
            }
        }

        // Last dose
        SageCard(padding = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(Icons.Outlined.Medication)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.last_dose), style = CaptionStyle)
                    val last = dosesToday.firstOrNull()
                    if (last != null) {
                        Text(timeFormat.format(Date(last.timestamp)), style = RowTitleStyle)
                        Text(listOf(last.name, last.dosage).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = DesignTokens.Muted)
                    } else {
                        Text(stringResource(R.string.last_dose_none), style = RowTitleStyle)
                    }
                }
                PillButton(stringResource(R.string.log_dose), onClick = onOpenMeds, height = 44.dp)
            }
        }

        // Vital signs
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.vitals_title), style = SectionStyle)
            val none = stringResource(R.string.value_none)
            val live = stringResource(R.string.vitals_live)
            @Composable fun measuredAt(t: Long?): String? = t?.let {
                val time = timeFormat.format(Date(it))
                if (isToday(it)) stringResource(R.string.vitals_at_today, time)
                else stringResource(R.string.vitals_at_day, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)), time)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    Icons.Outlined.FavoriteBorder, stringResource(R.string.vitals_hr),
                    (liveHr ?: latest.heartRate)?.toString() ?: none, stringResource(R.string.unit_bpm), Modifier.weight(1f),
                    caption = if (liveHr != null) live else measuredAt(latest.heartRateAt),
                )
                MetricTile(
                    Icons.Outlined.Air, stringResource(R.string.vitals_breathing),
                    latest.breathing?.let { String.format(java.util.Locale.getDefault(), "%.0f", it) } ?: none,
                    stringResource(R.string.unit_per_min), Modifier.weight(1f),
                    caption = measuredAt(latest.breathingAt),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    Icons.Outlined.WaterDrop, stringResource(R.string.vitals_spo2),
                    latest.spo2?.toString() ?: none, stringResource(R.string.unit_percent), Modifier.weight(1f),
                    caption = measuredAt(latest.spo2At),
                )
                MetricTile(Icons.Outlined.Speed, stringResource(R.string.vitals_bp), none, stringResource(R.string.unit_mmhg), Modifier.weight(1f))
            }
            Text(stringResource(R.string.vitals_hint), style = CaptionStyle)
        }
    }
}

@Composable
private fun SummaryStat(value: String, @StringRes labelRes: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, color = DesignTokens.Ink)
        Text(stringResource(labelRes), style = CaptionStyle)
    }
}
