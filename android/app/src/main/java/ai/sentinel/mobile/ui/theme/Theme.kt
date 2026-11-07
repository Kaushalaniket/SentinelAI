package ai.sentinel.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SentinelNavy = Color(0xFF0B1220)
val SentinelBlue = Color(0xFF2F81F7)
val SentinelTeal = Color(0xFF17B4A0)
val SentinelRed = Color(0xFFE5534B)
val SentinelAmber = Color(0xFFD29922)
val SentinelGreen = Color(0xFF3FB950)
val SentinelBackground = Color(0xFF070B16)
val SentinelSurface = Color(0xFF0B1020)
val SentinelCard = Color(0xFF111A2E)
val SentinelLine = Color(0xFF5BC0F8)
val SentinelWave = Color(0xFF7DD3FC)
val SentinelGrid = Color(0xFF2A3548)
val SentinelTextMuted = Color(0xFF9AA7B8)
val SentinelText = Color(0xFFE5E7EB)
val SentinelTextSecondary = Color(0xFF94A3B8)
val SentinelCyan = Color(0xFF22D3EE)

/** Extra-bright accented text used for titles on dark cards. */
val SentinelTitleText = Color(0xFFF8FAFC)
val SentinelTitleTextSecondary = Color(0xFFCBD5E1)

private val Dark = darkColorScheme(
    primary = SentinelBlue,
    secondary = SentinelTeal,
    background = SentinelBackground,
    surface = Color(0xFF131C2B),
    error = SentinelRed,
)

private val Light = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    secondary = Color(0xFF0E8F7F),
    background = Color(0xFFF6F8FB),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFC0392B),
)

@Composable
fun SentinelTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
