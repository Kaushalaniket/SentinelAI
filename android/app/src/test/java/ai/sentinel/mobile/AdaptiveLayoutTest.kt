package ai.sentinel.mobile

import ai.sentinel.mobile.ui.adaptive.WindowSize
import ai.sentinel.mobile.ui.adaptive.columnsFor
import ai.sentinel.mobile.ui.adaptive.metricsFor
import ai.sentinel.mobile.ui.adaptive.windowSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed on the JVM: verifies the breakpoints and the grid arithmetic that make the layout
 * adaptive, including the widths of real form factors. No device or emulator is required for
 * this, unlike the Compose UI tests.
 */
class AdaptiveLayoutTest {

    @Test
    fun widthSizeClassBreakpointsMatchMaterialThree() {
        assertEquals(WindowSize.COMPACT, windowSizeFor(320f))   // small phone
        assertEquals(WindowSize.COMPACT, windowSizeFor(360f))   // common phone
        assertEquals(WindowSize.COMPACT, windowSizeFor(411f))   // large phone
        assertEquals(WindowSize.COMPACT, windowSizeFor(599.9f))
        assertEquals(WindowSize.MEDIUM, windowSizeFor(600f))    // 7–8" tablet / folded foldable
        assertEquals(WindowSize.MEDIUM, windowSizeFor(673f))    // unfolded Pixel Fold inner screen
        assertEquals(WindowSize.MEDIUM, windowSizeFor(839.9f))
        assertEquals(WindowSize.EXPANDED, windowSizeFor(840f))  // 10–13" tablet
        assertEquals(WindowSize.EXPANDED, windowSizeFor(1280f)) // Idea Tab Pro landscape
        assertEquals(WindowSize.EXPANDED, windowSizeFor(1366f))
    }

    @Test
    fun phoneFitsTwoStatTilesPerRow() {
        // 360 dp phone minus 2 x 14 dp screen padding.
        assertEquals(2, columnsFor(widthDp = 332f, minItemWidthDp = 140f, spacingDp = 10f, itemCount = 5))
    }

    @Test
    fun smallFiveInchPhoneDoesNotOverflow() {
        val available = 292f // 320 dp minus padding
        val columns = columnsFor(available, 140f, 10f, 5)
        val needed = columns * 140f + (columns - 1) * 10f
        assertEquals(2, columns)
        assertTrue("row of $columns tiles needs $needed dp, available $available dp", needed <= available)
    }

    @Test
    fun tabletSpreadsAllFiveTilesAcrossOneRow() {
        assertEquals(5, columnsFor(1196f, 172f, 14f, 5))
    }

    @Test
    fun neverReturnsMoreColumnsThanItemsOrFewerThanOne() {
        assertEquals(3, columnsFor(2000f, 172f, 14f, 3))
        assertEquals(1, columnsFor(2000f, 172f, 14f, 0))
        assertEquals(1, columnsFor(0f, 172f, 14f, 9))
    }

    @Test
    fun everyFormFactorKeepsCellsInsideTheAvailableWidth() {
        val widths = listOf(280f, 320f, 360f, 393f, 411f, 480f, 600f, 673f, 720f, 840f, 900f, 1024f, 1280f, 1366f)
        for (width in widths) {
            for (minTile in listOf(140f, 156f, 172f)) {
                val columns = columnsFor(width, minTile, 12f, 8)
                val used = columns * minTile + (columns - 1) * 12f
                assertTrue(
                    "width=$width minTile=$minTile columns=$columns used=$used",
                    used <= width || columns == 1
                )
            }
        }
    }

    @Test
    fun metricsGrowFromCompactToExpanded() {
        val compact = metricsFor(WindowSize.COMPACT)
        val medium = metricsFor(WindowSize.MEDIUM)
        val expanded = metricsFor(WindowSize.EXPANDED)

        assertTrue(compact.gap.value < medium.gap.value)
        assertTrue(medium.gap.value < expanded.gap.value)
        assertTrue(compact.screenPadding.value < expanded.screenPadding.value)
        assertTrue(compact.chartHeight.value < medium.chartHeight.value)
        assertTrue(medium.chartHeight.value < expanded.chartHeight.value)
        assertTrue(compact.sparkHeight.value < expanded.sparkHeight.value)
        assertTrue(compact.statMinWidth.value < expanded.statMinWidth.value)
        assertTrue(compact.maxContentWidth.value < expanded.maxContentWidth.value)
        assertTrue(!compact.twoPane)
        assertTrue(medium.twoPane && expanded.twoPane)
    }
}
