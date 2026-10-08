package com.medicina.daedaluscompanion.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicina.daedaluscompanion.ble.ConnectionState
import com.medicina.daedaluscompanion.utils.DaedalusApplication
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val app = activity?.application as? DaedalusApplication ?: return
    val scope = rememberCoroutineScope()

    val chestStrapBLE = app.chestStrapBLE
    val watchClient = app.watchClient

    val connectionState by chestStrapBLE.connectionState.collectAsStateWithLifecycle()
    var isWatchConnected by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    // BLE Permissions
    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    var hasBlePermission by remember { mutableStateOf(
        blePermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    ) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasBlePermission = permissions.values.all { it }
        if (hasBlePermission) {
            chestStrapBLE.scanAndConnect()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Text("Configure your profile", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DesignTokens.Primary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .background(DesignTokens.Background)
                .padding(DesignTokens.Spacing16)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing24)
        ) {
            // Personal Information
            SettingsSection(title = "Participant Profile") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Participant ID") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(DesignTokens.RadiusSmall),
                    singleLine = true
                )
            }

            // Device Connections
            SettingsSection(title = "Device Connections") {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing12)) {
                    DeviceToggleRow(
                        name = "Samsung Watch5 Pro",
                        subtitle = if (isWatchConnected) "Connected" else "Tap to connect",
                        connected = isWatchConnected,
                        onToggle = {
                            scope.launch {
                                if (isWatchConnected) {
                                    watchClient.disconnect()
                                    isWatchConnected = false
                                } else {
                                    watchClient.connect()
                                    isWatchConnected = watchClient.isConnected()
                                }
                            }
                        },
                        icon = Icons.Default.Watch
                    )
                    DeviceToggleRow(
                        name = "Cycplus H2 Chest Strap",
                        subtitle = when {
                            !hasBlePermission -> "Grant BLE permissions"
                            connectionState == ConnectionState.CONNECTED -> "Connected"
                            connectionState == ConnectionState.SCANNING -> "Scanning..."
                            connectionState == ConnectionState.CONNECTING -> "Connecting..."
                            connectionState == ConnectionState.ERROR -> "Error — tap to retry"
                            else -> "Tap to connect"
                        },
                        connected = connectionState == ConnectionState.CONNECTED,
                        onToggle = {
                            if (!hasBlePermission) {
                                permissionLauncher.launch(blePermissions)
                            } else if (connectionState == ConnectionState.CONNECTED) {
                                chestStrapBLE.disconnect()
                            } else {
                                chestStrapBLE.scanAndConnect()
                            }
                        },
                        icon = Icons.Default.Favorite
                    )
                }
            }

            // About
            SettingsSection(title = "About") {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing8)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Version", color = DesignTokens.TextSecondary, fontSize = 14.sp)
                        Text("0.1.0", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    HorizontalDivider(color = DesignTokens.Border)
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Protocol", color = DesignTokens.TextSecondary, fontSize = 14.sp)
                        Text("v0.1", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing12)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = DesignTokens.TextPrimary)
        Surface(
            shape = RoundedCornerShape(DesignTokens.RadiusMedium),
            color = DesignTokens.Surface,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(DesignTokens.Spacing16), content = content)
        }
    }
}

@Composable
fun DeviceToggleRow(
    name: String,
    subtitle: String,
    connected: Boolean,
    onToggle: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(DesignTokens.RadiusSmall)),
                color = if (connected) DesignTokens.Success.copy(alpha = 0.1f) else DesignTokens.Primary.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (connected) DesignTokens.Success else DesignTokens.Primary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(DesignTokens.Spacing12))
            Column {
                Text(name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(subtitle, color = if (connected) DesignTokens.Success else DesignTokens.TextSecondary, fontSize = 12.sp)
            }
        }
        Switch(
            checked = connected,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = DesignTokens.Success,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = DesignTokens.Border
            )
        )
    }
}
