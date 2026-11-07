package ai.sentinel.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the real activity: the dashboard renders and every module is reachable.
 * Navigation shape depends on the window size class (bottom bar + More sheet on phones,
 * rail or sidebar on wider windows), so the helper opens "More" only when the label is not
 * already on screen.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun open(label: String) {
        if (rule.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()) {
            rule.onAllNodesWithText("More").onFirst().performClick()
            rule.waitForIdle()
        }
        rule.onAllNodesWithText(label).onFirst().performClick()
        rule.waitForIdle()
    }

    @Test
    fun dashboardRendersWithHonestSubtitle() {
        rule.onAllNodesWithText("Dashboard").onFirst().assertExists()
        rule.onAllNodesWithText("Everything below is read from this device's own database.")
            .onFirst()
            .assertExists()
    }

    @Test
    fun firewallModuleIsReachable() {
        open("Firewall")
        rule.onAllNodesWithText("Default allow. Nothing is blocked unless you add a rule here.")
            .onFirst()
            .assertExists()
    }

    @Test
    fun alertsReportsAndSettingsAreReachable() {
        open("Alerts")
        rule.onAllNodesWithText("Every alert carries either AI or Rule provenance.").onFirst().assertExists()
        open("Reports")
        rule.onAllNodesWithText("Aggregated from stored rows. Nothing is extrapolated.").onFirst().assertExists()
        open("Settings")
        rule.onAllNodesWithText("All settings apply immediately.").onFirst().assertExists()
    }
}
