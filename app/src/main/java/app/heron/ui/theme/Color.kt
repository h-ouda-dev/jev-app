package app.heron.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Heron palette. Named after the scene the brand comes from: still lagoon
 * water, misty mornings, and the heron's saffron beak as the one bright accent.
 */
object HeronPalette {
    // Brand
    val Lagoon900 = Color(0xFF0E3B43) // icon background, deepest brand tone
    val Lagoon800 = Color(0xFF134A54)
    val Lagoon700 = Color(0xFF17605F) // primary (light)
    val Lagoon500 = Color(0xFF2F8C86)
    val Lagoon300 = Color(0xFF7FD3CB) // primary (dark)
    val Lagoon100 = Color(0xFFCDEBE6)
    val Ripple = Color(0xFF3FA7A3)
    val Beak = Color(0xFFF2A93B) // the accent
    val BeakDeep = Color(0xFF8A5A00)
    val Feather = Color(0xFFF4F1EA)

    // Neutrals (light)
    val Mist = Color(0xFFF6F4EE)
    val Ink = Color(0xFF14232A)
    val InkSoft = Color(0xFF4F5B60)

    // Neutrals (dark)
    val NightWater = Color(0xFF0B1A1F)
    val NightMist = Color(0xFFE3ECEC)
}

/** Meaningful colours that the Material scheme doesn't cover. */
data class HeronColors(
    val safe: Color,
    val safeContainer: Color,
    val caution: Color,
    val cautionContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val urgent: Color,
    val later: Color,
    val digest: Color,
    val muted: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroSoft: Color,
    val accent: Color,
    val categories: List<Color>,
)

val LightHeronColors = HeronColors(
    safe = Color(0xFF2E7D57),
    safeContainer = Color(0xFFD7F0E1),
    caution = Color(0xFFB36A00),
    cautionContainer = Color(0xFFFFE6C2),
    danger = Color(0xFFC4372F),
    dangerContainer = Color(0xFFFBDCD8),
    urgent = Color(0xFFC4372F),
    later = Color(0xFF17605F),
    digest = Color(0xFF5B52C7),
    muted = Color(0xFF6F7C82),
    heroStart = Color(0xFF0E3B43),
    heroEnd = Color(0xFF17605F),
    onHero = Color(0xFFF4F1EA),
    onHeroSoft = Color(0xB3F4F1EA),
    accent = HeronPalette.Beak,
    categories = listOf(
        Color(0xFF3E8E5B), Color(0xFFD9822B), Color(0xFF2F7DB8), Color(0xFFB0478F),
        Color(0xFF7A6BD1), Color(0xFFC4372F), Color(0xFFCC9A06), Color(0xFF2E9A9A),
        Color(0xFF8D6E63), Color(0xFF546E7A), Color(0xFF17605F), Color(0xFF9E9E9E),
    ),
)

val DarkHeronColors = HeronColors(
    safe = Color(0xFF6FD39A),
    safeContainer = Color(0xFF113524),
    caution = Color(0xFFF6BC62),
    cautionContainer = Color(0xFF3D2A07),
    danger = Color(0xFFFF8A80),
    dangerContainer = Color(0xFF4A1916),
    urgent = Color(0xFFFF8A80),
    later = Color(0xFF7FD3CB),
    digest = Color(0xFFB3A9FF),
    muted = Color(0xFF9AA7AD),
    heroStart = Color(0xFF0F3C44),
    heroEnd = Color(0xFF16585A),
    onHero = Color(0xFFF4F1EA),
    onHeroSoft = Color(0xB3F4F1EA),
    accent = HeronPalette.Beak,
    categories = listOf(
        Color(0xFF7FD39B), Color(0xFFF2A65A), Color(0xFF7DB8E8), Color(0xFFE38CC6),
        Color(0xFFB3A9FF), Color(0xFFFF8A80), Color(0xFFF0CB5A), Color(0xFF73D1D1),
        Color(0xFFC2A196), Color(0xFF9DB4BF), Color(0xFF7FD3CB), Color(0xFFBDBDBD),
    ),
)
