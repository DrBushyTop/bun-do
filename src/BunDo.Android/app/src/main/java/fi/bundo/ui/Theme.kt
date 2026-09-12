package fi.bundo.ui

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF245B48), onPrimary = Color.White,
    primaryContainer = Color(0xFFBCEBD2), onPrimaryContainer = Color(0xFF082116),
    secondary = Color(0xFF52635A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8DC), onSecondaryContainer = Color(0xFF102018),
    tertiary = Color(0xFF3E6374), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC2E8FC), onTertiaryContainer = Color(0xFF001F2A),
    background = Color(0xFFF8F9F4), onBackground = Color(0xFF191D19),
    surface = Color(0xFFF8F9F4), onSurface = Color(0xFF191D19),
    surfaceVariant = Color(0xFFE0E4DC), onSurfaceVariant = Color(0xFF444C44),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F4EE),
    surfaceContainer = Color(0xFFECEFE8), surfaceContainerHigh = Color(0xFFE6E9E2),
    surfaceContainerHighest = Color(0xFFE0E4DC),
    surfaceBright = Color(0xFFF8F9F4), surfaceDim = Color(0xFFD8DBD4),
    outline = Color(0xFF747D73), outlineVariant = Color(0xFFC4CCC1),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2E322D), inverseOnSurface = Color(0xFFF0F2EC),
    inversePrimary = Color(0xFFA1D2BA), surfaceTint = Color(0xFF245B48),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA1D2BA), onPrimary = Color(0xFF083827),
    primaryContainer = Color(0xFF28513F), onPrimaryContainer = Color(0xFFD6F8E4),
    secondary = Color(0xFFB7CBC0), onSecondary = Color(0xFF23352C),
    secondaryContainer = Color(0xFF394B41), onSecondaryContainer = Color(0xFFD5E8DC),
    tertiary = Color(0xFFA6CCDF), onTertiary = Color(0xFF083544),
    tertiaryContainer = Color(0xFF264B5B), onTertiaryContainer = Color(0xFFC2E8FC),
    background = Color(0xFF111511), onBackground = Color(0xFFE0E4DD),
    surface = Color(0xFF111511), onSurface = Color(0xFFE0E4DD),
    surfaceVariant = Color(0xFF444C44), onSurfaceVariant = Color(0xFFC2CAC0),
    surfaceContainerLowest = Color(0xFF0C100C), surfaceContainerLow = Color(0xFF191D18),
    surfaceContainer = Color(0xFF1D211C), surfaceContainerHigh = Color(0xFF282C26),
    surfaceContainerHighest = Color(0xFF33372F),
    surfaceBright = Color(0xFF373B34), surfaceDim = Color(0xFF111511),
    outline = Color(0xFF8C958B), outlineVariant = Color(0xFF444C44),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE0E4DD), inverseOnSurface = Color(0xFF2E322D),
    inversePrimary = Color(0xFF245B48), surfaceTint = Color(0xFFA1D2BA),
)

private fun type(size: Int, line: Int, medium: Boolean = false) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = line.sp,
)

private val BunDoTypography = Typography(
    headlineLarge = type(32, 40),
    headlineSmall = type(24, 32),
    titleLarge = type(22, 28, true),
    titleMedium = type(16, 24, true),
    bodyLarge = type(16, 24),
    bodyMedium = type(14, 20),
    labelLarge = type(14, 20, true),
    labelMedium = type(12, 16, true),
)

@Composable
fun BunDoTheme(appearance: String, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = BunDoTypography,
        content = content,
    )
}
