package com.moblized.stormdeck.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Extra colors the Material scheme doesn't cover. */
@Immutable
data class StormColors(
    val card: Color,
    val cardHigh: Color,
    val dim: Color,
    val warning: Color,
    val watch: Color,
    val advisory: Color,
    val warm: Color,
    val cool: Color,
    val good: Color,
)

val LocalStorm = staticCompositionLocalOf {
    StormColors(
        Color(0xFF131C2E), Color(0xFF1A2540), Color(0xFF94A3B8), Color(0xFFEF4444), Color(0xFFF59E0B),
        Color(0xFFEAB308), Color(0xFFF97316), Color(0xFF38BDF8), Color(0xFF22C55E),
    )
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF00243A),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFD5EEFF),
    secondary = Color(0xFFA5B4FC),
    onSecondary = Color(0xFF1E1B4B),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1A2540),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainer = Color(0xFF111A2C),
    surfaceContainerHigh = Color(0xFF16213A),
    surfaceContainerHighest = Color(0xFF1C2946),
    surfaceContainerLow = Color(0xFF0F1727),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF243049),
    error = Color(0xFFF87171),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEFF),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = Color(0xFF4F46E5),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE5EBF3),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F4F9),
    surfaceContainerHighest = Color(0xFFE6ECF4),
    surfaceContainerLow = Color(0xFFFAFCFE),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
)

private val DarkExtra = StormColors(
    card = Color(0xFF131C2E),
    cardHigh = Color(0xFF1A2540),
    dim = Color(0xFF94A3B8),
    warning = Color(0xFFEF4444),
    watch = Color(0xFFF59E0B),
    advisory = Color(0xFFEAB308),
    warm = Color(0xFFF97316),
    cool = Color(0xFF38BDF8),
    good = Color(0xFF22C55E),
)

private val LightExtra = StormColors(
    card = Color(0xFFFFFFFF),
    cardHigh = Color(0xFFF0F4F9),
    dim = Color(0xFF64748B),
    warning = Color(0xFFDC2626),
    watch = Color(0xFFD97706),
    advisory = Color(0xFFCA8A04),
    warm = Color(0xFFEA580C),
    cool = Color(0xFF0284C7),
    good = Color(0xFF16A34A),
)

private val AppType = Typography(
    displayLarge = TextStyle(fontSize = 84.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun StormTheme(dark: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStorm provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppType, content = content)
    }
}
