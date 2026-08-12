package com.shahar.shaharsat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MissionColorScheme = darkColorScheme(
    primary = MissionColors.AccentGreen,
    onPrimary = MissionColors.Background,
    secondary = MissionColors.AccentBlue,
    onSecondary = MissionColors.Background,
    error = MissionColors.AccentRed,
    onError = MissionColors.TextPrimary,
    background = MissionColors.Background,
    onBackground = MissionColors.TextPrimary,
    surface = MissionColors.Surface,
    onSurface = MissionColors.TextPrimary,
    surfaceVariant = MissionColors.SurfaceVariant,
    onSurfaceVariant = MissionColors.TextSecondary,
    outline = MissionColors.Outline
)

private val MissionTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp, fontFamily = FontFamily.Monospace),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
)

/**
 * The app is dark-only by design (mission-control brief) — this ignores
 * [isSystemInDarkTheme] deliberately rather than offering a light theme.
 */
@Composable
fun ShaharSatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MissionColorScheme,
        typography = MissionTypography,
        content = content
    )
}
