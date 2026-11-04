package com.medicina.daedaluscompanion.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.data.LocalStore
import com.medicina.daedaluscompanion.data.TremorAnalyzer
import com.medicina.daedaluscompanion.data.analyzeTremorFile
import com.medicina.daedaluscompanion.data.tremorSummary
import com.medicina.daedaluscompanion.wear.SessionState
import com.medicina.daedaluscompanion.wear.WatchDataClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun CuesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val watchClient = remember { WatchDataClient(context) }
    val store = remember { LocalStore(context) }

    var isSessionActive by remember { mutableStateOf(SessionState.isSessionActive) }
    var activeMode by remember { mutableStateOf(SessionState.getActiveSessionMode()) }
    var selectedMode by remember { mutableStateOf(HAPTIC_MODES.first()) }
    var isSending by remember { mutableStateOf(false) }
    // Shown under the Start button: the watch isn't connected, or didn't answer in time.
    var startProblem by remember { mutableStateOf<Int?>(null) }
    var isStartingCheck by remember { mutableStateOf(false) }
    var checkProblem by remember { mutableStateOf<Int?>(null) }
    // Newest tremor check file and its summary ("Rhythm 5.2 Hz · Moderate").
    var lastCheck by remember { mutableStateOf<Pair<SessionItem, String>?>(null) }

    LaunchedEffect(Unit) {
        var seenCheck: Pair<String, Long>? = null
        while (true) {
            isSessionActive = SessionState.isSessionActive
            activeMode = SessionState.getActiveSessionMode()
            // Re-analyse when a new tremor check file arrives from the watch.
            val newest = withContext(Dispatchers.IO) {
                loadSessions(context).firstOrNull { it.mode == TremorAnalyzer.MODE }
            }
            val key = newest?.let { it.fileName to it.size }
            if (key != seenCheck) {
                seenCheck = key
                lastCheck = newest?.let { item ->
                    val result = withContext(Dispatchers.IO) { analyzeTremorFile(File(sessionsDir(context), item.fileName)) }
                    item to tremorSummary(context, result)
                }
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    // A tremor check uses the watch's session too, but it isn't a cue.
    val tremorActive = isSessionActive && activeMode == TremorAnalyzer.MODE
    val cueActive = isSessionActive && !tremorActive

    val shownMode = if (cueActive) modeById(activeMode) ?: selectedMode else selectedMode
    // Rate per mode, set with the slider in that mode's steps and saved on the phone.
    var rates by remember {
        mutableStateOf(HAPTIC_MODES.associate { m ->
            // Saved rates from an older step size snap to the nearest step.
            val saved = store.getInt(LocalStore.cueBpmKey(m.id), m.bpm)
            val r = rateRange(m)
            m.id to (Math.round(saved / r.step.toFloat()) * r.step).coerceIn(r.min, r.max)
        })
    }
    val shownRate = rates[shownMode.id] ?: shownMode.bpm

    fun setRate(mode: HapticMode, bpm: Int, sendNow: Boolean) {
        val r = rateRange(mode)
        val snapped = (bpm.coerceIn(r.min, r.max) / r.step) * r.step
        rates = rates + (mode.id to snapped)
        if (!sendNow) return
        store.setInt(LocalStore.cueBpmKey(mode.id), snapped)
        // Keep the watch in sync so "I'm stuck" and a running cue use the new rate.
        scope.launch { runCatching { watchClient.sendCueRate(mode.id, snapped) } }
    }
    /**
     * Sends a start or stop to the watch and waits for it to confirm. Returns the problem to show
     * (watch not connected, or no answer in time), or null.
     */
    suspend fun commandWatch(wasActive: Boolean, send: suspend () -> Unit): Int? {
        if (!runCatching { watchClient.isConnected() }.getOrDefault(false)) return R.string.cue_watch_missing
        // The UI reflects start/stop only once the watch acknowledges it
        // over /session/control — see WatchDataListener.
        runCatching { send() }
        // Keep "Starting…" up until the watch answers, or give up after a while.
        val answered = withTimeoutOrNull(WATCH_REPLY_TIMEOUT_MS) {
            while (SessionState.isSessionActive == wasActive) delay(250)
        } != null
        isSessionActive = SessionState.isSessionActive
        activeMode = SessionState.getActiveSessionMode()
        return if (answered) null else R.string.cue_no_reply
    }
    fun toggleSession() {
        if (isSending) return
        isSending = true
        startProblem = null
        val wasActive = isSessionActive
        scope.launch {
            try {
                startProblem = commandWatch(wasActive) {
                    if (wasActive) watchClient.sendSessionStop()
                    else watchClient.sendSessionStart(selectedMode.id, rates[selectedMode.id] ?: selectedMode.bpm)
                }
            } finally {
                isSending = false
            }
        }
    }
    fun startTremorCheck() {
        if (isStartingCheck || isSessionActive) return
        isStartingCheck = true
        checkProblem = null
        scope.launch {
            try {
                checkProblem = commandWatch(wasActive = false) { watchClient.sendTremorCheckStart() }
            } finally {
                isStartingCheck = false
            }
        }
    }

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.cues_title), style = TitleStyle)
            Text(stringResource(R.string.cues_lead), fontSize = 14.sp, color = DesignTokens.Muted)
        }

        // Mode picker
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HAPTIC_MODES.forEach { mode ->
                val isOn = mode.id == shownMode.id
                Surface(
                    onClick = { selectedMode = mode },
                    enabled = !isSessionActive,
                    shape = RoundedCornerShape(16.dp),
                    color = if (isOn) DesignTokens.SageDeep else DesignTokens.Surface,
                    modifier = Modifier.weight(1f).height(72.dp).semantics { role = Role.Tab; selected = isOn },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(mode.icon, null, tint = if (isOn) Color.White else DesignTokens.Sage, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(mode.labelRes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isOn) Color.White else DesignTokens.Ink, maxLines = 1)
                    }
                }
            }
        }

        // Rhythm hero
        SageCard(color = DesignTokens.SageDeep) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(shownMode.icon, null, tint = DesignTokens.OnSageDeep, modifier = Modifier.size(18.dp))
                Text(stringResource(shownMode.descRes), fontSize = 13.sp, color = DesignTokens.OnSageDeep)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$shownRate", fontSize = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.4).sp, color = Color.White)
                Text(
                    stringResource(
                        when (shownMode.id) {
                            "walking" -> R.string.unit_steps_min
                            "rest" -> R.string.unit_breaths_min
                            else -> R.string.unit_pulses_min
                        }
                    ),
                    fontSize = 15.sp, color = DesignTokens.OnSageDeep, modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (cueActive) {
                Text(stringResource(R.string.cue_running), fontSize = 13.sp, color = DesignTokens.OnSageDeep)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = ::toggleSession,
                enabled = !isSending && !tremorActive && !isStartingCheck,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White, contentColor = DesignTokens.SageDeep,
                    disabledContainerColor = Color.White, disabledContentColor = DesignTokens.SageDeep,
                ),
                border = if (cueActive) BorderStroke(1.dp, DesignTokens.SageLine) else null,
            ) {
                if (isSending) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = DesignTokens.SageDeep, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (cueActive) R.string.cue_stopping else R.string.cue_starting), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(if (cueActive) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (cueActive) R.string.cue_stop else R.string.cue_start), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            startProblem?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(it), fontSize = 13.sp, color = DesignTokens.OnSageDeep,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        // Rate for the shown mode
        val range = rateRange(shownMode)
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cue_rate), style = RowTitleStyle)
                    Text(stringResource(R.string.cue_rate_sub, stringResource(shownMode.labelRes), range.step), style = CaptionStyle)
                }
                RateStepButton(Icons.Outlined.Remove, stringResource(R.string.cue_rate_down, range.step), enabled = shownRate > range.min) {
                    setRate(shownMode, shownRate - range.step, sendNow = true)
                }
                Text("$shownRate", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink, modifier = Modifier.padding(horizontal = 8.dp))
                RateStepButton(Icons.Outlined.Add, stringResource(R.string.cue_rate_up, range.step), enabled = shownRate < range.max) {
                    setRate(shownMode, shownRate + range.step, sendNow = true)
                }
            }
            Slider(
                value = shownRate.toFloat(),
                onValueChange = { setRate(shownMode, Math.round(it), sendNow = false) },
                onValueChangeFinished = { setRate(shownMode, shownRate, sendNow = true) },
                valueRange = range.min.toFloat()..range.max.toFloat(),
                steps = (range.max - range.min) / range.step - 1,
                colors = SliderDefaults.colors(
                    thumbColor = DesignTokens.SageDeep,
                    activeTrackColor = DesignTokens.Sage,
                    inactiveTrackColor = DesignTokens.SageTrack,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.cue_slower, range.min), style = CaptionStyle)
                Text(stringResource(R.string.cue_faster, range.max), style = CaptionStyle)
            }
        }

        // Tremor check: not a cue mode. The watch records 20 s with the hand at rest.
        SageCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.GraphicEq, null, tint = DesignTokens.Sage, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.tremor_check_title), style = RowTitleStyle)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tremor_check_body), fontSize = 14.sp, color = DesignTokens.Ink, lineHeight = 20.sp)
            if (tremorActive) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.tremor_check_running), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.SageDeep,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = ::startTremorCheck,
                enabled = !isStartingCheck && !isSessionActive && !isSending,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesignTokens.SageDeep, contentColor = Color.White,
                    disabledContainerColor = DesignTokens.SageTrack, disabledContentColor = DesignTokens.SageDeep,
                ),
            ) {
                if (isStartingCheck) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = DesignTokens.SageDeep, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.cue_starting), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tremor_check_start), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            checkProblem?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(it), fontSize = 13.sp, color = DesignTokens.Ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            lastCheck?.let { (item, summary) ->
                val time = if (isToday(item.date)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.date)) else item.dateTimeLabel
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.tremor_check_last, summary, time), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.tremor_check_note), style = CaptionStyle)
        }

        // Automatic cues: detection isn't built yet, so they're shown as coming, never as on.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cue_auto_title), style = SectionStyle)
            Surface(shape = RoundedCornerShape(50), color = DesignTokens.SageTint) {
                Text(
                    stringResource(R.string.coming_soon), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = DesignTokens.SageDeep, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Text(stringResource(R.string.cue_auto_note), style = CaptionStyle)
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            ListRow(stringResource(R.string.cue_walking), stringResource(R.string.cue_walking_sub), icon = Icons.AutoMirrored.Outlined.DirectionsWalk, showDivider = true)
            ListRow(stringResource(R.string.cue_tremor), stringResource(R.string.cue_tremor_sub), icon = Icons.Outlined.PanTool, showDivider = true)
            ListRow(stringResource(R.string.cue_breathing), stringResource(R.string.cue_breathing_sub), icon = Icons.Outlined.Air)
        }

        // Stand-up detection does run on the Galaxy Watch, so this one keeps its switch.
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            CueToggle(store, LocalStore.CUE_STAND, false, R.string.cue_stand, R.string.cue_stand_sub, divider = false) { on ->
                // The watch runs stand-up detection in the background while this is on.
                scope.launch { runCatching { watchClient.sendStandPrompt(on) } }
            }
        }
    }
}

private const val WATCH_REPLY_TIMEOUT_MS = 10_000L

private data class RateRange(val min: Int, val max: Int, val step: Int)

/** Breathe is a breathing pacer (breaths per minute); the other modes are pulses per minute. */
private fun rateRange(mode: HapticMode) =
    if (mode.id == "rest") RateRange(6, 20, 1) else RateRange(40, 140, 10)

@Composable
private fun RateStepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = DesignTokens.SageTint,
            contentColor = DesignTokens.SageDeep,
            disabledContainerColor = DesignTokens.SageTrack,
            disabledContentColor = DesignTokens.SageLine,
        ),
    ) { Icon(icon, contentDescription = description) }
}

@Composable
private fun CueToggle(
    store: LocalStore,
    key: String,
    default: Boolean,
    @StringRes title: Int,
    @StringRes sub: Int,
    divider: Boolean,
    onChanged: (Boolean) -> Unit = {},
) {
    var on by remember { mutableStateOf(store.getBoolean(key, default)) }
    ListRow(
        title = stringResource(title),
        subtitle = stringResource(sub),
        showDivider = divider,
        trailing = { SageSwitch(on, { on = it; store.setBoolean(key, it); onChanged(it) }) },
    )
}
