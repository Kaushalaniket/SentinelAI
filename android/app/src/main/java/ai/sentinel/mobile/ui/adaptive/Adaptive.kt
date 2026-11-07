package ai.sentinel.mobile.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Metrics of the size class the activity reported, available to every screen. */
val LocalAdaptive = staticCompositionLocalOf { metricsFor(WindowSize.COMPACT) }

@Composable
fun Adaptively(size: WindowSize, content: @Composable () -> Unit) {
    val metrics = remember(size) { metricsFor(size) }
    CompositionLocalProvider(LocalAdaptive provides metrics, content = content)
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun WindowWidthSizeClass.toWindowSize(): WindowSize = when (this) {
    WindowWidthSizeClass.Compact -> WindowSize.COMPACT
    WindowWidthSizeClass.Medium -> WindowSize.MEDIUM
    WindowWidthSizeClass.Expanded -> WindowSize.EXPANDED
    else -> WindowSize.COMPACT
}

/**
 * Lays [items] out in as many equal-width columns as fit, wrapping onto new rows. Column counts
 * come from [columnsFor], so the last cell width plus gaps can never exceed the container width.
 */
@Composable
fun <T> ResponsiveGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    minItemWidth: Dp = LocalAdaptive.current.statMinWidth,
    spacing: Dp = LocalAdaptive.current.gap,
    item: @Composable (T) -> Unit
) {
    if (items.isEmpty()) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = columnsFor(
            widthDp = maxWidth.value,
            minItemWidthDp = minItemWidth.value,
            spacingDp = spacing.value,
            itemCount = items.size
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    rowItems.forEach { value ->
                        Box(modifier = Modifier.weight(1f)) { item(value) }
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Two panels side by side once there is room for two readable panes (medium and expanded
 * windows), stacked vertically on phones and on any pane narrower than [minPaneWidth].
 */
@Composable
fun TwoPane(
    modifier: Modifier = Modifier,
    minPaneWidth: Dp = 340.dp,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit
) {
    val metrics = LocalAdaptive.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sideBySide = metrics.twoPane && maxWidth >= minPaneWidth * 2 + metrics.gap
        if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.gap)
            ) {
                Box(modifier = Modifier.weight(1f)) { left() }
                Box(modifier = Modifier.weight(1f)) { right() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(metrics.gap)) {
                left()
                right()
            }
        }
    }
}
