package ai.sentinel.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import ai.sentinel.mobile.ui.adaptive.WindowSize

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

/**
 * Typography grows with the window so a 13" tablet does not show phone-sized text, while
 * compact phones keep the Material 3 defaults. User font-scale settings still apply on top,
 * because every size stays in sp.
 */
private fun typographyFor(size: WindowSize): Typography {
    val base = Typography()
    return when (size) {
        WindowSize.COMPACT -> base
        WindowSize.MEDIUM -> base.copy(
            displaySmall = base.displaySmall.copy(fontSize = 38.sp, lineHeight = 44.sp),
            headlineMedium = base.headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp),
            headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp),
            titleMedium = base.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
            bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        )
        WindowSize.EXPANDED -> base.copy(
            displaySmall = base.displaySmall.copy(fontSize = 42.sp, lineHeight = 48.sp),
            headlineMedium = base.headlineMedium.copy(fontSize = 32.sp, lineHeight = 40.sp),
            headlineSmall = base.headlineSmall.copy(fontSize = 27.sp, lineHeight = 34.sp),
            titleMedium = base.titleMedium.copy(fontSize = 18.sp, lineHeight = 25.sp),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = base.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.5.sp),
        )
    }
}

@Composable
fun SentinelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    size: WindowSize = WindowSize.COMPACT,
    content: @Composable () -> Unit
) {
    val typography = remember(size) { typographyFor(size) }
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = typography,
        content = content
    )
}
