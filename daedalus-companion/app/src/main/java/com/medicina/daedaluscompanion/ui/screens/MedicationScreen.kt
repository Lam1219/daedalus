package com.medicina.daedaluscompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

data class MedicationLog(
    val name: String,
    val dosage: String,
    val timestamp: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationScreen() {
    var medications by remember { mutableStateOf(listOf<MedicationLog>()) }
    var showAddDialog by remember { mutableStateOf(false) }

    val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Medication", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Text("Track doses", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DesignTokens.Primary),
                actions = {
                    // Fitts's Law - large, accessible FAB-style button
                    IconButton(onClick = { showAddDialog = true }) {
                        Surface(modifier = Modifier.size(36.dp).clip(CircleShape), color = Color.White.copy(alpha = 0.2f)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .background(DesignTokens.Background)
                .padding(DesignTokens.Spacing16)
                .verticalScroll(rememberScrollState())
        ) {
            if (medications.isEmpty()) {
                // Empty state (clear, actionable)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(modifier = Modifier.size(64.dp).clip(CircleShape), color = DesignTokens.Primary.copy(alpha = 0.1f)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Medication, null, tint = DesignTokens.Primary, modifier = Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.height(DesignTokens.Spacing16))
                    Text("No medications logged", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = DesignTokens.TextPrimary)
                    Text("Tap + to log a dose", color = DesignTokens.TextSecondary, fontSize = 13.sp)
                }
            } else {
                // Grouped by day (Miller's Law)
                Text("Today", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(bottom = DesignTokens.Spacing12))
                medications.forEach { med ->
                    MedicationLogItem(med, timeFormat)
                    Spacer(Modifier.height(DesignTokens.Spacing8))
                }
            }
        }
    }

    // Add Medication Dialog
    if (showAddDialog) {
        var medName by remember { mutableStateOf("") }
        var medDosage by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Log Medication", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing16)) {
                    OutlinedTextField(
                        value = medName,
                        onValueChange = { medName = it },
                        label = { Text("Medication name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = medDosage,
                        onValueChange = { medDosage = it },
                        label = { Text("Dosage (e.g., 100mg)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (medName.isNotBlank()) {
                            medications = medications + MedicationLog(medName, medDosage, System.currentTimeMillis())
                            showAddDialog = false
                        }
                    },
                    enabled = medName.isNotBlank(),
                    shape = RoundedCornerShape(DesignTokens.RadiusSmall)
                ) { Text("Save Dose", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = DesignTokens.TextSecondary) }
            },
            shape = RoundedCornerShape(DesignTokens.RadiusLarge)
        )
    }
}

@Composable
fun MedicationLogItem(med: MedicationLog, timeFormat: SimpleDateFormat) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.RadiusMedium),
        color = DesignTokens.Surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(DesignTokens.Spacing16),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(DesignTokens.RadiusSmall)),
                    color = DesignTokens.Primary.copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Medication, null, tint = DesignTokens.Primary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(DesignTokens.Spacing12))
                Column {
                    Text(med.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(med.dosage, color = DesignTokens.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            Text(timeFormat.format(Date(med.timestamp)), color = DesignTokens.TextSecondary, fontSize = 13.sp)
        }
    }
}
