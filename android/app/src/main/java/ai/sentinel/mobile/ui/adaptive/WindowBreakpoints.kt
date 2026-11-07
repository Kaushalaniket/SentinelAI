package ai.sentinel.mobile.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 width size classes, expressed in dp of window width:
 *
 *  - COMPACT  (0–599 dp):   5–6" phones in portrait, folded flip-phone covers
 *  - MEDIUM   (600–839 dp): 7–8" tablets, large phones in landscape, unfolded foldables
 *  - EXPANDED (840 dp +):   10–13" tablets (Lenovo Idea Tab Pro), foldables fully open
 *
 * These thresholds are also what `androidx.compose.material3.windowsizeclass` reports, so the
 * same values are used by both the real WindowSizeClass and this pure-Kotlin mapping (which is
 * unit-tested in `AdaptiveLayoutTest`).
 */
enum class WindowSize { COMPACT, MEDIUM, EXPANDED }

fun windowSizeFor(widthDp: Float): WindowSize = when {
    widthDp < 600f -> WindowSize.COMPACT
    widthDp < 840f -> WindowSize.MEDIUM
    else -> WindowSize.EXPANDED
}

/**
 * How many equal-width cells fit in [widthDp] when every cell needs at least [minItemWidthDp]
 * and neighbours are separated by [spacingDp]. Never returns more than [itemCount], and never
 * returns a count whose total width would exceed the available width, which is what keeps cards
 * from overflowing on small phones.
 */
fun columnsFor(widthDp: Float, minItemWidthDp: Float, spacingDp: Float, itemCount: Int): Int {
    if (itemCount <= 0) return 1
    val minWidth = minItemWidthDp.coerceAtLeast(1f)
    val gap = spacingDp.coerceAtLeast(0f)
    var columns = 1
    while (columns < itemCount) {
        val needed = minWidth * (columns + 1) + gap * columns
        if (needed > widthDp) break
        columns++
    }
    return columns
}

/** Per-size spacing, chart and card metrics so the same composables scale across devices. */
data class AdaptiveMetrics(
    val size: WindowSize,
    val gap: Dp,
    val screenPadding: Dp,
    val cardPadding: Dp,
    val maxContentWidth: Dp,
    val chartHeight: Dp,
    val sparkHeight: Dp,
    val statMinWidth: Dp,
    val gaugeMinWidth: Dp,
    val twoPane: Boolean,
)

fun metricsFor(size: WindowSize): AdaptiveMetrics = when (size) {
    WindowSize.COMPACT -> AdaptiveMetrics(
        size = size,
        gap = 10.dp,
        screenPadding = 14.dp,
        cardPadding = 14.dp,
        maxContentWidth = 620.dp,
        chartHeight = 68.dp,
        sparkHeight = 30.dp,
        statMinWidth = 140.dp,
        gaugeMinWidth = 140.dp,
        twoPane = false,
    )
    WindowSize.MEDIUM -> AdaptiveMetrics(
        size = size,
        gap = 12.dp,
        screenPadding = 18.dp,
        cardPadding = 16.dp,
        maxContentWidth = 900.dp,
        chartHeight = 80.dp,
        sparkHeight = 34.dp,
        statMinWidth = 156.dp,
        gaugeMinWidth = 156.dp,
        twoPane = true,
    )
    WindowSize.EXPANDED -> AdaptiveMetrics(
        size = size,
        gap = 14.dp,
        screenPadding = 22.dp,
        cardPadding = 18.dp,
        maxContentWidth = 1240.dp,
        chartHeight = 96.dp,
        sparkHeight = 38.dp,
        statMinWidth = 172.dp,
        gaugeMinWidth = 168.dp,
        twoPane = true,
    )
}
