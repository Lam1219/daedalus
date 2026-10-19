package com.medicina.daedaluscompanion.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.session.BlockCategory
import com.medicina.daedaluscompanion.session.ProtocolBlock
import com.medicina.daedaluscompanion.session.ProtocolBlocks

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

@StringRes
fun BlockCategory.labelRes(): Int = when (this) {
    BlockCategory.SYNC -> R.string.cat_sync
    BlockCategory.REST -> R.string.cat_rest
    BlockCategory.BREATHING -> R.string.cat_breathing
    BlockCategory.STRESS -> R.string.cat_stress
    BlockCategory.MOVEMENT -> R.string.cat_movement
    BlockCategory.TREMOR -> R.string.cat_tremor
    BlockCategory.GAIT -> R.string.cat_gait
    BlockCategory.HAPTIC -> R.string.cat_haptic
    else -> R.string.cat_setup
}

@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf(calibrationCategories.first()) }
    var activeBlockId by remember { mutableStateOf<String?>(null) }

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        BackHeader(stringResource(R.string.calib_title), onBack)
        Text(stringResource(R.string.calib_lead), fontSize = 14.sp, color = DesignTokens.Muted)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            calibrationCategories.forEach { category ->
                val on = category == selected
                Surface(
                    onClick = { selected = category },
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) DesignTokens.SageDeep else DesignTokens.Surface,
                    modifier = Modifier.height(40.dp).semantics { role = Role.Tab; this.selected = on },
                ) {
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(category.labelRes()), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else DesignTokens.Ink)
                    }
                }
            }
        }

        ProtocolBlocks.getByCategory(selected).forEach { block ->
            ProtocolBlockCard(
                block = block,
                isActive = activeBlockId == block.id,
                onStart = { activeBlockId = block.id },
                onStop = { activeBlockId = null },
            )
        }
    }
}

@Composable
fun ProtocolBlockCard(block: ProtocolBlock, isActive: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    SageCard(color = if (isActive) DesignTokens.SageTint else DesignTokens.Surface) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(block.displayName, style = RowTitleStyle, modifier = Modifier.weight(1f))
            Text(block.targetDurationText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Sage)
        }
        Text(block.description, style = CaptionStyle.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.start), onClick = onStart, enabled = !isActive, icon = Icons.Outlined.PlayArrow, height = 44.dp, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = onStop,
                enabled = isActive,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, DesignTokens.SageLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.SageDeep),
            ) {
                Icon(Icons.Outlined.Stop, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.stop), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
