package lv.bibliogid.manga.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF2A3B5C),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF9C7BD6),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF141922),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1D2430),
    onSurfaceVariant = Color(0xFFC3CBDA),
    outline = Color(0xFF39424F),
    error = Color(0xFFFF6B6B)
)

private val Light = lightColorScheme(
    primary = Color(0xFF3B5BA5),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F5)
)

val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, letterSpacing = 0.4.sp)
)

@Composable
fun BiblioTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        content = content
    )
}
