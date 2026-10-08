package com.medicina.daedaluscompanion.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.wearable.Wearable
import com.medicina.daedaluscompanion.wear.SessionState
import com.medicina.daedaluscompanion.wear.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class SessionItem(
    val fileName: String,
    val displayName: String,
    val date: Long,
    val size: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf(listOf<SessionItem>()) }
    var isSessionActive by remember { mutableStateOf(SessionState.isSessionActive) }
    var activeMode by remember { mutableStateOf(SessionState.getActiveSessionMode()) }

    // Poll session state every second
    LaunchedEffect(Unit) {
        while (true) {
            isSessionActive = SessionState.isSessionActive
            activeMode = SessionState.getActiveSessionMode()
            kotlinx.coroutines.delay(1000)
        }
    }

    var watchConnected by remember { mutableStateOf<Boolean?>(null) }

    // Check watch connection
    LaunchedEffect(Unit) {
        try {
            val nodeClient = Wearable.getNodeClient(context)
            val nodes = nodeClient.connectedNodes.await()
            watchConnected = nodes.isNotEmpty()
            android.util.Log.i("HomeScreen", "Watch connected: ${nodes.size} node(s)")
            nodes.forEach { node ->
                android.util.Log.i("HomeScreen", "  Node: ${node.displayName}, id=${node.id}")
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeScreen", "Connection check failed: ${e.message}")
            watchConnected = false
        }
    }

    // Load sessions on appear
    LaunchedEffect(Unit) {
        sessions = loadSessions(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Daedalus", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Text("Parkinson's Research Companion", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DesignTokens.Primary),
                actions = {
                    IconButton(onClick = { sessions = loadSessions(context) }) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .background(DesignTokens.Background)
                .padding(DesignTokens.Spacing16),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing24)
        ) {
            // Session Status Card
            SessionStatusCard(isSessionActive, activeMode)

            // Watch Connection Status
            if (watchConnected != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(DesignTokens.RadiusMedium),
                    colors = CardDefaults.cardColors(
                        containerColor = if (watchConnected == true) DesignTokens.Success.copy(alpha = 0.08f) else DesignTokens.Error.copy(alpha = 0.08f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(DesignTokens.Spacing16),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Watch,
                            null,
                            tint = if (watchConnected == true) DesignTokens.Success else DesignTokens.Error
                        )
                        Spacer(Modifier.width(DesignTokens.Spacing12))
                        Column {
                            Text(
                                if (watchConnected == true) "Watch Connected" else "Watch Not Connected",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                            Text(
                                if (watchConnected == true) "Sessions will sync automatically" else "Pair watch via Wear OS app",
                                color = DesignTokens.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Quick Actions
            QuickActionsSection(onSessionToggle = { /* Handled by watch */ }, isSessionActive)

            // Sessions List
            SessionsSection(sessions, context, isSessionActive) { session ->
                downloadSession(context, session)
            }
        }
    }
}

@Composable
fun SessionStatusCard(active: Boolean, activeMode: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignTokens.RadiusLarge),
        colors = CardDefaults.cardColors(
            containerColor = if (active) DesignTokens.Success.copy(alpha = 0.1f) else DesignTokens.Surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(DesignTokens.Spacing24)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Session", fontSize = 14.sp, color = DesignTokens.TextSecondary, fontWeight = FontWeight.Medium)
                    if (active) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(DesignTokens.Success)
                            )
                            Spacer(Modifier.width(DesignTokens.Spacing8))
                            Text("Recording — ${activeMode?.uppercase()}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Success)
                        }
                    } else {
                        Text("Ready to start", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DesignTokens.TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionsSection(onSessionToggle: () -> Unit, sessionActive: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing12)) {
        Text("Quick Actions", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.TextPrimary)

        Button(
            onClick = onSessionToggle,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(DesignTokens.RadiusMedium),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (sessionActive) DesignTokens.Error else DesignTokens.Primary
            ),
            enabled = false // Disabled — session controlled from watch
        ) {
            Icon(if (sessionActive) Icons.Default.Stop else Icons.Default.PlayArrow, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(DesignTokens.Spacing8))
            Text(if (sessionActive) "Running on Watch" else "Start on Watch", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SessionsSection(sessions: List<SessionItem>, context: Context, isSessionActive: Boolean, onDownload: (SessionItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing12)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.TextPrimary)
            Text("${sessions.size} file(s)", fontSize = 12.sp, color = DesignTokens.TextSecondary)
        }

        if (isSessionActive) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DesignTokens.RadiusMedium),
                colors = CardDefaults.cardColors(containerColor = DesignTokens.Success.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(DesignTokens.Spacing16),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = DesignTokens.Success,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(DesignTokens.Spacing12))
                    Column {
                        Text("Session in progress", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Downloads disabled until session ends", color = DesignTokens.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        if (sessions.isEmpty() && !isSessionActive) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DesignTokens.RadiusMedium),
                colors = CardDefaults.cardColors(containerColor = DesignTokens.Surface)
            ) {
                Box(
                    modifier = Modifier.padding(DesignTokens.Spacing24).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, null, tint = DesignTokens.TextTertiary, modifier = Modifier.size(32.dp))
                        Text("No sessions yet", color = DesignTokens.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = DesignTokens.Spacing8))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing8)
            ) {
                items(sessions) { session ->
                    SessionRow(session, onDownload, isSessionActive)
                }
            }
        }
    }
}

@Composable
fun SessionRow(session: SessionItem, onDownload: (SessionItem) -> Unit, isSessionActive: Boolean) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.RadiusMedium),
        color = DesignTokens.Surface,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(DesignTokens.Spacing12).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(DesignTokens.RadiusSmall)),
                    color = DesignTokens.Primary.copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Analytics, null, tint = DesignTokens.Primary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(DesignTokens.Spacing12))
                Column {
                    Text(session.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("${formatFileSize(session.size)} · ${formatDate(session.date)}", color = DesignTokens.TextSecondary, fontSize = 11.sp)
                }
            }
            IconButton(
                onClick = { onDownload(session) },
                enabled = !isSessionActive
            ) {
                Icon(
                    Icons.Default.Download,
                    null,
                    tint = if (isSessionActive) DesignTokens.TextTertiary else DesignTokens.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

fun loadSessions(context: Context): List<SessionItem> {
    val sessionsDir = File(context.filesDir.parent, "files/sessions")
    if (!sessionsDir.exists()) return emptyList()

    return sessionsDir.listFiles()?.map { file ->
        val name = file.nameWithoutExtension
        val mode = extractMode(name)
        val date = extractDate(name)
        SessionItem(
            fileName = file.name,
            displayName = "session_$mode",
            date = date ?: file.lastModified(),
            size = file.length(),
        )
    }?.sortedByDescending { it.date } ?: emptyList()
}

fun extractMode(fileName: String): String {
    val parts = fileName.split("_")
    return if (parts.size >= 2) parts[1] else "unknown"
}

fun extractDate(fileName: String): Long? {
    val regex = """session_\w+_(\d{8}_\d{6})""".toRegex()
    val match = regex.find(fileName)
    return match?.groupValues?.get(1)?.let { dateStr ->
        try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }
}

fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(timestamp))
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

fun downloadSession(context: Context, session: SessionItem) {
    val sessionsDir = File(context.filesDir.parent, "files/sessions")
    val sourceFile = File(sessionsDir, session.fileName)
    if (!sourceFile.exists()) return

    // Use the system share intent to save/open the file
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        sourceFile
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(shareIntent, "Download session"))
}
