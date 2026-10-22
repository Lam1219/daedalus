package com.medicina.daedaluscompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SageColors = lightColorScheme(
    primary = DesignTokens.SageDeep,
    onPrimary = Color.White,
    primaryContainer = DesignTokens.SageTint,
    onPrimaryContainer = DesignTokens.SageDeep,
    secondary = DesignTokens.Sage,
    onSecondary = Color.White,
    background = DesignTokens.Ground,
    onBackground = DesignTokens.Ink,
    surface = DesignTokens.Surface,
    onSurface = DesignTokens.Ink,
    surfaceVariant = DesignTokens.SageTrack,
    onSurfaceVariant = DesignTokens.Muted,
    outline = DesignTokens.SageLine,
    error = DesignTokens.Alert,
)

@Composable
fun DaedalusTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val f = DesignTokens.Manrope
    MaterialTheme(
        colorScheme = SageColors,
        typography = Typography(
            displayLarge = base.displayLarge.copy(fontFamily = f),
            headlineLarge = base.headlineLarge.copy(fontFamily = f),
            headlineMedium = base.headlineMedium.copy(fontFamily = f),
            titleLarge = base.titleLarge.copy(fontFamily = f),
            titleMedium = base.titleMedium.copy(fontFamily = f),
            titleSmall = base.titleSmall.copy(fontFamily = f),
            bodyLarge = base.bodyLarge.copy(fontFamily = f),
            bodyMedium = base.bodyMedium.copy(fontFamily = f),
            bodySmall = base.bodySmall.copy(fontFamily = f),
            labelLarge = base.labelLarge.copy(fontFamily = f),
            labelMedium = base.labelMedium.copy(fontFamily = f),
            labelSmall = base.labelSmall.copy(fontFamily = f),
        ),
        content = content,
    )
}

val TitleStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, color = DesignTokens.Ink)
val SectionStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
val RowTitleStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
val CaptionStyle = TextStyle(fontSize = 12.sp, color = DesignTokens.Muted)

/** Screen frame: sage ground, 56dp top inset, 20dp gutters. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DesignTokens.Ground)
            .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** Borderless white card. */
@Composable
fun SageCard(
    modifier: Modifier = Modifier,
    color: Color = DesignTokens.Surface,
    radius: Dp = DesignTokens.RadiusXLarge,
    padding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun IconBadge(icon: ImageVector, size: Dp = 44.dp, background: Color = DesignTokens.SageTint, tint: Color = DesignTokens.SageDeep) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    dark: Boolean = true,
    height: Dp = 48.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        contentPadding = PaddingValues(horizontal = 18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) DesignTokens.SageDeep else DesignTokens.Surface,
            contentColor = if (dark) Color.White else DesignTokens.SageDeep,
            disabledContainerColor = DesignTokens.SwitchOff,
            disabledContentColor = DesignTokens.Muted,
        ),
        elevation = null,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = DesignTokens.Manrope)
    }
}

@Composable
fun SageSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = DesignTokens.Sage,
            checkedBorderColor = DesignTokens.Sage,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = DesignTokens.SwitchOff,
            uncheckedBorderColor = DesignTokens.Outline,
        ),
    )
}

/** A row inside a card: optional leading icon, title + subtitle, trailing slot. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = if (onClick != null) {
        { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = DesignTokens.NavInactive) }
    } else null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = DesignTokens.Sage, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = RowTitleStyle)
                if (subtitle != null) Text(subtitle, style = CaptionStyle)
            }
            trailing?.invoke()
        }
        if (showDivider) HorizontalDivider(color = DesignTokens.Divider)
    }
}

/** Small white tile: icon + label, then value + unit. */
@Composable
fun MetricTile(icon: ImageVector, label: String, value: String, unit: String, modifier: Modifier = Modifier, caption: String? = null) {
    SageCard(modifier = modifier, radius = DesignTokens.RadiusMedium, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = DesignTokens.Sage, modifier = Modifier.size(16.dp))
            Text(label, style = CaptionStyle, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink, fontFamily = DesignTokens.Manrope)
            Text(" $unit", fontSize = 12.sp, color = DesignTokens.Muted, fontFamily = DesignTokens.Manrope, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(caption, style = CaptionStyle.copy(fontSize = 11.sp), maxLines = 1)
        }
    }
}
