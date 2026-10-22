package com.medicina.daedaluscompanion.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medicina.daedaluscompanion.R

/** Sage palette from the Daedalus App Design canvas. */
object DesignTokens {
    val Ground = Color(0xFFF5F6F1)
    val Surface = Color(0xFFFFFFFF)
    val SageTint = Color(0xFFE4EBDF)
    val SageTrack = Color(0xFFECEFE9)
    val SageLine = Color(0xFFA9BBA3)
    val Sage = Color(0xFF4E6447)
    val SageDeep = Color(0xFF34452F)
    val OnSageDeep = Color(0xFFD7E2D1)
    val Ink = Color(0xFF1E2620)
    val Muted = Color(0xFF5F6B61)
    val NavInactive = Color(0xFF66726A)
    val Divider = Color(0xFFECEFE9)
    val SwitchOff = Color(0xFFD5DCD1)
    /** Borders of fields, unselected choices, checkboxes and off switches: 3.1:1 on white (WCAG 1.4.11). */
    val Outline = Color(0xFF869681)
    val Alert = Color(0xFFA15C2E)

    // Legacy names kept so older screens keep compiling against the new palette.
    val Primary = SageDeep
    val Background = Ground
    val TextPrimary = Ink
    val TextSecondary = Muted
    val TextTertiary = SageLine
    val Border = Divider
    val Success = Sage
    val Error = Alert

    val Spacing4 = 4.dp
    val Spacing8 = 8.dp
    val Spacing12 = 12.dp
    val Spacing16 = 16.dp
    val Spacing20 = 20.dp
    val Spacing24 = 24.dp
    val Spacing32 = 32.dp

    val RadiusSmall = 12.dp
    val RadiusMedium = 18.dp
    val RadiusLarge = 20.dp
    val RadiusXLarge = 24.dp

    val Manrope = FontFamily(
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_medium, FontWeight.Medium),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_bold, FontWeight.Bold),
    )
}
