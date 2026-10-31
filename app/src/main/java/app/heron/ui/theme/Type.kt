package app.heron.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.heron.R

/** Display face: characterful, a little editorial. Used for headlines and big numbers. */
val Bricolage = FontFamily(
    Font(R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_extrabold, FontWeight.ExtraBold),
)

/** Text face: warm, very legible geometric sans for everything else. */
val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
)

private fun display(size: Int, line: Int, weight: FontWeight = FontWeight.Bold, tracking: Double = -0.02) =
    TextStyle(fontFamily = Bricolage, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)

private fun text(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(fontFamily = Jakarta, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)

val HeronTypography = Typography(
    displayLarge = display(52, 56, FontWeight.ExtraBold, -0.03),
    displayMedium = display(42, 46, FontWeight.ExtraBold, -0.03),
    displaySmall = display(34, 40, FontWeight.Bold),
    headlineLarge = display(30, 36, FontWeight.Bold),
    headlineMedium = display(26, 32, FontWeight.Bold),
    headlineSmall = display(22, 28, FontWeight.Bold),
    titleLarge = text(20, 26, FontWeight.Bold, -0.01),
    titleMedium = text(16, 22, FontWeight.Bold),
    titleSmall = text(14, 20, FontWeight.SemiBold),
    bodyLarge = text(16, 24, FontWeight.Normal),
    bodyMedium = text(14, 21, FontWeight.Normal),
    bodySmall = text(12, 17, FontWeight.Normal),
    labelLarge = text(14, 20, FontWeight.SemiBold),
    labelMedium = text(12, 16, FontWeight.SemiBold, 0.01),
    labelSmall = text(11, 14, FontWeight.SemiBold, 0.03),
)
