package app.heron.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.heron.core.money.BudgetCategory
import app.heron.core.scam.ScamVerdict
import app.heron.core.triage.TriageBucket

private val LightScheme = lightColorScheme(
    primary = HeronPalette.Lagoon700,
    onPrimary = Color.White,
    primaryContainer = HeronPalette.Lagoon100,
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6366),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7E6),
    onSecondaryContainer = Color(0xFF0E2124),
    tertiary = HeronPalette.BeakDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2B8),
    onTertiaryContainer = Color(0xFF2C1700),
    error = Color(0xFFC4372F),
    onError = Color.White,
    errorContainer = Color(0xFFFBDCD8),
    onErrorContainer = Color(0xFF410002),
    background = HeronPalette.Mist,
    onBackground = HeronPalette.Ink,
    surface = HeronPalette.Mist,
    onSurface = HeronPalette.Ink,
    surfaceVariant = Color(0xFFE4E7E2),
    onSurfaceVariant = HeronPalette.InkSoft,
    surfaceTint = HeronPalette.Lagoon700,
    outline = Color(0xFF7A8589),
    outlineVariant = Color(0xFFDCDDD6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFBF8),
    surfaceContainer = Color(0xFFF0EEE7),
    surfaceContainerHigh = Color(0xFFEAE8E1),
    surfaceContainerHighest = Color(0xFFE3E1DA),
    inverseSurface = Color(0xFF26343A),
    inverseOnSurface = Color(0xFFEFF1EE),
    inversePrimary = HeronPalette.Lagoon300,
)

private val DarkScheme = darkColorScheme(
    primary = HeronPalette.Lagoon300,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF0E4F4C),
    onPrimaryContainer = Color(0xFFA6F0E7),
    secondary = Color(0xFFB1CBCD),
    onSecondary = Color(0xFF1C3437),
    secondaryContainer = Color(0xFF324B4E),
    onSecondaryContainer = Color(0xFFCDE7E9),
    tertiary = Color(0xFFF6BC62),
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF5C3C00),
    onTertiaryContainer = Color(0xFFFFDDB0),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A1916),
    onErrorContainer = Color(0xFFFFDAD6),
    background = HeronPalette.NightWater,
    onBackground = HeronPalette.NightMist,
    surface = HeronPalette.NightWater,
    onSurface = HeronPalette.NightMist,
    surfaceVariant = Color(0xFF2C3F44),
    onSurfaceVariant = Color(0xFFA9B7B8),
    surfaceTint = HeronPalette.Lagoon300,
    outline = Color(0xFF6F7E80),
    outlineVariant = Color(0xFF243840),
    surfaceContainerLowest = Color(0xFF071216),
    surfaceContainerLow = Color(0xFF10222A),
    surfaceContainer = Color(0xFF142930),
    surfaceContainerHigh = Color(0xFF1A3138),
    surfaceContainerHighest = Color(0xFF213A41),
    inverseSurface = HeronPalette.NightMist,
    inverseOnSurface = Color(0xFF26343A),
    inversePrimary = HeronPalette.Lagoon700,
)

val HeronShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LocalHeronColors = staticCompositionLocalOf { LightHeronColors }

/** Access Heron's semantic colours: `Heron.colors.danger`. */
object Heron {
    val colors: HeronColors
        @Composable @ReadOnlyComposable get() = LocalHeronColors.current
}

/**
 * Heron uses its own brand palette (not wallpaper colours) so the product
 * looks the same for everyone, like a proper brand.
 */
@Composable
fun HeronTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHeronColors provides if (dark) DarkHeronColors else LightHeronColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = HeronTypography,
            shapes = HeronShapes,
            content = content,
        )
    }
}

@Composable @ReadOnlyComposable
fun bucketColor(b: TriageBucket): Color = when (b) {
    TriageBucket.URGENT -> Heron.colors.urgent
    TriageBucket.CAN_WAIT -> Heron.colors.later
    TriageBucket.DIGEST -> Heron.colors.digest
    TriageBucket.SILENCE -> Heron.colors.muted
}

@Composable @ReadOnlyComposable
fun verdictColor(v: ScamVerdict): Color = when (v) {
    ScamVerdict.SAFE -> Heron.colors.safe
    ScamVerdict.SUSPICIOUS -> Heron.colors.caution
    ScamVerdict.LIKELY_SCAM -> Heron.colors.danger
}

@Composable @ReadOnlyComposable
fun verdictContainer(v: ScamVerdict): Color = when (v) {
    ScamVerdict.SAFE -> Heron.colors.safeContainer
    ScamVerdict.SUSPICIOUS -> Heron.colors.cautionContainer
    ScamVerdict.LIKELY_SCAM -> Heron.colors.dangerContainer
}

@Composable @ReadOnlyComposable
fun categoryColor(c: BudgetCategory): Color = Heron.colors.categories[c.ordinal % Heron.colors.categories.size]
