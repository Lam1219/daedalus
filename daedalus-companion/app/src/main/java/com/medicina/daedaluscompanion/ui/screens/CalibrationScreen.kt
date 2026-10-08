package com.medicina.daedaluscompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medicina.daedaluscompanion.session.ProtocolBlocks
import com.medicina.daedaluscompanion.session.BlockCategory
import com.medicina.daedaluscompanion.ui.screens.DesignTokens

// Categories with blocks, grouped logically (Miller's Law - 7±2)
val calibrationCategories = listOf(
    BlockCategory.SYNC,
    BlockCategory.REST,
    BlockCategory.BREATHING,
    BlockCategory.STRESS,
    BlockCategory.MOVEMENT,
    BlockCategory.TREMOR,
    BlockCategory.GAIT,
    BlockCategory.HAPTIC,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen() {
    var selectedTabIndex by remember { mutableStateOf(0) }
    var activeBlockId by remember { mutableStateOf<String?>(null) }

    val selectedCategory = calibrationCategories[selectedTabIndex]

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Calibration", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Text("Protocol Blocks", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DesignTokens.Primary)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).background(DesignTokens.Background)) {
            // Category tabs (Hick's Law - clear labels, limited choices)
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = DesignTokens.Background,
                edgePadding = DesignTokens.Spacing16,
                divider = {}
            ) {
                calibrationCategories.forEachIndexed { index, category ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(category.label, fontSize = 12.sp, fontWeight = if (selectedTabIndex == index) FontWeight.SemiBold else FontWeight.Normal) }
                    )
                }
            }

            // Protocol blocks (Jakob's Law - familiar card pattern)
            val blocks = ProtocolBlocks.getByCategory(selectedCategory)
            Column(
                modifier = Modifier
                    .padding(DesignTokens.Spacing16)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing12)
            ) {
                blocks.forEach { block ->
                    ProtocolBlockCard(
                        block = block,
                        isActive = activeBlockId == block.id,
                        onStart = { activeBlockId = block.id },
                        onStop = { activeBlockId = null }
                    )
                }
            }
        }
    }
}

@Composable
fun ProtocolBlockCard(
    block: com.medicina.daedaluscompanion.session.ProtocolBlock,
    isActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(DesignTokens.RadiusMedium),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) DesignTokens.Success.copy(alpha = 0.08f) else DesignTokens.Surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 4.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(DesignTokens.Spacing16)) {
            // Block name + duration
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(block.displayName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    block.targetDurationText,
                    color = DesignTokens.Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Description
            Text(block.description, color = DesignTokens.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = DesignTokens.Spacing4))

            // Action buttons (Fitts's Law - large touch targets)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = DesignTokens.Spacing16),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing8)
            ) {
                Button(
                    onClick = onStart,
                    enabled = !isActive,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(DesignTokens.RadiusSmall),
                    colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Primary.copy(alpha = if (isActive) 0.3f else 1f))
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(DesignTokens.Spacing4))
                    Text("Start", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onStop,
                    enabled = isActive,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(DesignTokens.RadiusSmall),
                    colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Error)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(DesignTokens.Spacing4))
                    Text("Stop", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

val BlockCategory.label: String
    get() = when (this) {
        BlockCategory.SYNC -> "Sync"
        BlockCategory.REST -> "Rest"
        BlockCategory.BREATHING -> "Breath"
        BlockCategory.STRESS -> "Stress"
        BlockCategory.MOVEMENT -> "Motion"
        BlockCategory.TREMOR -> "Tremor"
        BlockCategory.GAIT -> "Gait"
        BlockCategory.HAPTIC -> "Haptic"
        else -> name
    }
