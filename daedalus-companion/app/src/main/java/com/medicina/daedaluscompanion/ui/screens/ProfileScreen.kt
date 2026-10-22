package com.medicina.daedaluscompanion.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.data.CloudBackup
import com.medicina.daedaluscompanion.auth.AuthState
import com.medicina.daedaluscompanion.ble.ConnectionState
import com.medicina.daedaluscompanion.utils.DaedalusApplication
import com.medicina.daedaluscompanion.wear.SessionState
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ProfileScreen(onOpenCalibration: () -> Unit, onOpenSessions: () -> Unit, onOpenSchedule: () -> Unit, onOpenData: () -> Unit) {
    val context = LocalContext.current
    val app = (context as? Activity)?.application as? DaedalusApplication ?: return
    val scope = rememberCoroutineScope()

    val chestStrapBLE = app.chestStrapBLE
    val watchClient = app.watchClient
    val connectionState by chestStrapBLE.connectionState.collectAsStateWithLifecycle()
    var isWatchConnected by remember { mutableStateOf(false) }
    var sessionCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        isWatchConnected = runCatching { watchClient.isConnected() }.getOrDefault(false)
        while (true) {
            sessionCount = loadSessions(context).size
            kotlinx.coroutines.delay(3000)
        }
    }

    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    var hasBlePermission by remember {
        mutableStateOf(blePermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasBlePermission = permissions.values.all { it }
        if (hasBlePermission) chestStrapBLE.scanAndConnect()
    }

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.profile_title), style = TitleStyle)

        // Account
        val authState by app.auth.state.collectAsStateWithLifecycle()
        // Sign-out confirmation: null = hidden, true = everything is backed up, false = some isn't.
        var signOutBackedUp by remember { mutableStateOf<Boolean?>(null) }
        var checkingBackup by remember { mutableStateOf(false) }
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            val signedIn = authState as? AuthState.SignedIn
            ListRow(
                title = signedIn?.email ?: stringResource(if (signedIn != null) R.string.account_signed_in else R.string.account_guest),
                subtitle = when {
                    signedIn == null -> stringResource(R.string.account_guest_sub)
                    else -> stringResource(R.string.account_via_email)
                },
                icon = Icons.Outlined.AccountCircle,
                trailing = {
                    TextButton(
                        enabled = !checkingBackup,
                        onClick = {
                            if (signedIn == null) {
                                // Guest data stays; the person chooses what to do with it after signing in.
                                scope.launch { app.auth.signOut() }
                            } else {
                                // Back up first, then confirm: signing out clears this phone's health data.
                                checkingBackup = true
                                scope.launch {
                                    signOutBackedUp = CloudBackup.isFullyBackedUp(app)
                                    checkingBackup = false
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(if (signedIn != null) R.string.account_sign_out else R.string.account_sign_in),
                            color = DesignTokens.SageDeep, fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )
        }
        signOutBackedUp?.let { backedUp ->
            AlertDialog(
                onDismissRequest = { signOutBackedUp = null },
                containerColor = DesignTokens.Surface,
                title = { Text(stringResource(R.string.account_sign_out_title), style = SectionStyle) },
                text = {
                    Text(
                        stringResource(if (backedUp) R.string.account_sign_out_backed_up else R.string.account_sign_out_not_backed_up),
                        fontSize = 15.sp, color = DesignTokens.Ink,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        signOutBackedUp = null
                        CloudBackup.launch { signOutAndClear(app, force = true) }
                    }) {
                        Text(
                            stringResource(R.string.account_sign_out),
                            color = if (backedUp) DesignTokens.SageDeep else DesignTokens.Alert, fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { signOutBackedUp = null }) { Text(stringResource(R.string.cancel), color = DesignTokens.Muted) }
                },
            )
        }

        // Privacy: research opt-out (signed-in accounts only; guest data never leaves the phone)
        if (authState is AuthState.SignedIn) {
            // null while loading or when the choice couldn't be read (offline)
            var allowResearch by remember { mutableStateOf<Boolean?>(null) }
            var saveFailed by remember { mutableStateOf(false) }
            LaunchedEffect(authState) { allowResearch = app.auth.analysisOptOut()?.not() }
            SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                ListRow(
                    title = stringResource(R.string.data_research_title),
                    subtitle = stringResource(
                        when {
                            saveFailed -> R.string.data_research_failed
                            allowResearch == null -> R.string.data_research_unavailable
                            else -> R.string.data_research_profile_sub
                        },
                    ),
                    icon = Icons.Outlined.Science,
                    showDivider = true,
                    trailing = {
                        SageSwitch(
                            checked = allowResearch == true,
                            enabled = allowResearch != null,
                            onCheckedChange = { allow ->
                                val before = allowResearch
                                allowResearch = allow; saveFailed = false
                                scope.launch {
                                    if (!app.auth.setAnalysisOptOut(!allow)) { allowResearch = before; saveFailed = true }
                                }
                            },
                        )
                    },
                )
                Text(
                    stringResource(R.string.data_promise_short),
                    style = CaptionStyle,
                    modifier = Modifier.padding(start = 40.dp, top = 8.dp, bottom = 14.dp),
                )
            }
        }

        // Language
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Language, null, tint = DesignTokens.Sage, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.language), style = RowTitleStyle)
            }
            Spacer(Modifier.height(12.dp))
            val isFrench = Locale.getDefault().language == "fr"
            Row(
                Modifier.fillMaxWidth().background(DesignTokens.SageTrack, RoundedCornerShape(22.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LanguageOption("English", selected = !isFrench, modifier = Modifier.weight(1f)) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                }
                LanguageOption("Français", selected = isFrench, modifier = Modifier.weight(1f)) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr"))
                }
            }
        }

        // Devices
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.devices), style = RowTitleStyle, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
            DeviceRow(
                icon = Icons.Outlined.Watch,
                name = stringResource(R.string.device_watch),
                status = stringResource(if (isWatchConnected) R.string.device_connected else R.string.device_tap_connect),
                connected = isWatchConnected,
                divider = true,
                onToggle = {
                    scope.launch {
                        if (isWatchConnected) {
                            watchClient.disconnect(); isWatchConnected = false
                        } else {
                            watchClient.connect(); isWatchConnected = watchClient.isConnected()
                        }
                    }
                },
            )
            DeviceRow(
                icon = Icons.Outlined.MonitorHeart,
                name = stringResource(R.string.device_strap),
                status = stringResource(
                    when {
                        !hasBlePermission -> R.string.strap_grant
                        connectionState == ConnectionState.CONNECTED -> R.string.device_connected
                        connectionState == ConnectionState.SCANNING -> R.string.strap_scanning
                        connectionState == ConnectionState.CONNECTING -> R.string.strap_connecting
                        connectionState == ConnectionState.ERROR -> R.string.strap_error
                        else -> R.string.device_tap_connect
                    }
                ),
                connected = connectionState == ConnectionState.CONNECTED,
                divider = false,
                onToggle = {
                    when {
                        !hasBlePermission -> permissionLauncher.launch(blePermissions)
                        connectionState == ConnectionState.CONNECTED -> chestStrapBLE.disconnect()
                        else -> chestStrapBLE.scanAndConnect()
                    }
                },
            )
        }
        signOutBackedUp?.let { backedUp ->
            AlertDialog(
                onDismissRequest = { signOutBackedUp = null },
                containerColor = DesignTokens.Surface,
                title = { Text(stringResource(R.string.account_sign_out_title), style = SectionStyle) },
                text = {
                    Text(
                        stringResource(if (backedUp) R.string.account_sign_out_backed_up else R.string.account_sign_out_not_backed_up),
                        fontSize = 15.sp, color = DesignTokens.Ink,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        signOutBackedUp = null
                        CloudBackup.launch { signOutAndClear(app, force = true) }
                    }) {
                        Text(
                            stringResource(R.string.account_sign_out),
                            color = if (backedUp) DesignTokens.SageDeep else DesignTokens.Alert, fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { signOutBackedUp = null }) { Text(stringResource(R.string.cancel), color = DesignTokens.Muted) }
                },
            )
        }

        // More
        val dosesPerDay = remember { com.medicina.daedaluscompanion.data.LocalStore(context).schedule().size }
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            ListRow(
                stringResource(R.string.schedule_title),
                if (dosesPerDay == 0) stringResource(R.string.schedule_not_set)
                else pluralStringResource(R.plurals.schedule_count, dosesPerDay, dosesPerDay),
                Icons.Outlined.Medication,
                showDivider = true,
                onClick = onOpenSchedule,
            )
            ListRow(stringResource(R.string.profile_calibration), stringResource(R.string.profile_calibration_sub), Icons.Outlined.Tune, showDivider = true, onClick = onOpenCalibration)
            ListRow(stringResource(R.string.profile_sessions), pluralStringResource(R.plurals.profile_sessions_count, sessionCount, sessionCount), Icons.Outlined.FolderOpen, showDivider = true, onClick = onOpenSessions)
            ListRow(stringResource(R.string.data_title), stringResource(R.string.data_row_sub), Icons.Outlined.Shield, onClick = onOpenData)
        }

        Text(
            stringResource(R.string.disclaimer) + "\n" + stringResource(R.string.version, "0.1.0"),
            style = CaptionStyle,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) DesignTokens.Surface else Color.Transparent,
        modifier = modifier.height(40.dp).semantics { this.selected = selected },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, color = if (selected) DesignTokens.Ink else DesignTokens.Muted)
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, status: String, connected: Boolean, divider: Boolean, onToggle: () -> Unit) {
    Column {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon, size = 40.dp, background = if (connected) DesignTokens.SageTint else DesignTokens.SageTrack, tint = if (connected) DesignTokens.SageDeep else DesignTokens.Muted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
                Text(status, fontSize = 12.sp, fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal, color = if (connected) DesignTokens.Sage else DesignTokens.Muted)
            }
            SageSwitch(connected, { onToggle() })
        }
        if (divider) HorizontalDivider(color = DesignTokens.Divider)
    }
}

@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back), tint = DesignTokens.Ink)
        }
        Text(title, style = TitleStyle.copy(fontSize = 26.sp), modifier = Modifier.offset(x = (-12).dp))
    }
}

@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf(listOf<SessionItem>()) }
    var isSessionActive by remember { mutableStateOf(SessionState.isSessionActive) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            isSessionActive = SessionState.isSessionActive
            sessions = loadSessions(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BackHeader(stringResource(R.string.profile_sessions), onBack) }
            if (sessions.isNotEmpty()) {
                IconButton(onClick = { showDeleteConfirm = true }, enabled = !isSessionActive) {
                    Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.sessions_delete_all_cd), tint = if (isSessionActive) DesignTokens.SageLine else DesignTokens.Alert)
                }
            }
        }

        if (isSessionActive) {
            SageCard(color = DesignTokens.SageTint, radius = DesignTokens.RadiusLarge, padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = DesignTokens.Sage, strokeWidth = 2.dp)
                    Column {
                        Text(stringResource(R.string.sessions_in_progress), style = RowTitleStyle)
                        Text(stringResource(R.string.sessions_in_progress_sub), style = CaptionStyle)
                    }
                }
            }
        }

        if (sessions.isEmpty() && !isSessionActive) {
            SageCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = DesignTokens.SageLine, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sessions_empty), style = CaptionStyle)
                }
            }
        }

        groupSessionsByMode(sessions).forEach { (mode, items) ->
            Text("${mode?.let { stringResource(it.labelRes) } ?: stringResource(R.string.mode_other)} (${items.size})", style = SectionStyle)
            SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                items.forEachIndexed { i, session ->
                    ListRow(
                        title = session.dateTimeLabel,
                        subtitle = formatFileSize(session.size),
                        icon = mode?.icon ?: Icons.Outlined.Description,
                        showDivider = i < items.lastIndex,
                        trailing = {
                            IconButton(onClick = { downloadSession(context, session) }, enabled = !isSessionActive) {
                                Icon(Icons.Outlined.Download, stringResource(R.string.sessions_download_cd), tint = if (isSessionActive) DesignTokens.SageLine else DesignTokens.SageDeep)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = DesignTokens.Surface,
            shape = RoundedCornerShape(DesignTokens.RadiusXLarge),
            title = { Text(stringResource(R.string.sessions_delete_title), style = SectionStyle) },
            text = { Text(pluralStringResource(R.plurals.sessions_delete_body, sessions.size, sessions.size), color = DesignTokens.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    deleteAllSessions(context, sessions)
                    sessions = loadSessions(context)
                }) { Text(stringResource(R.string.delete), color = DesignTokens.Alert, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel), color = DesignTokens.Muted) }
            },
        )
    }
}
