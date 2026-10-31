package app.jev.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.jev.core.scam.ScamVerdict
import app.jev.core.triage.TriageBucket

@Composable
fun JevTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // Android 12+ (the S25 Ultra) gets Material You colours from the wallpaper.
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> fallbackDark
        else -> fallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val fallbackLight = lightColorScheme(primary = Color(0xFF1B3A5C))
private val fallbackDark = darkColorScheme(primary = Color(0xFF9CCAFF))

fun bucketColor(b: TriageBucket): Color = when (b) {
    TriageBucket.URGENT -> Color(0xFFD32F2F)
    TriageBucket.CAN_WAIT -> Color(0xFF1976D2)
    TriageBucket.DIGEST -> Color(0xFF7B1FA2)
    TriageBucket.SILENCE -> Color(0xFF757575)
}

fun verdictColor(v: ScamVerdict): Color = when (v) {
    ScamVerdict.SAFE -> Color(0xFF2E7D32)
    ScamVerdict.SUSPICIOUS -> Color(0xFFEF6C00)
    ScamVerdict.LIKELY_SCAM -> Color(0xFFC62828)
}
