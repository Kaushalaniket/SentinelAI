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
 * Module labels appear both in the navigation (sidebar on tablets, chips on phones) and as
 * screen titles, so the first match is clicked.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun open(label: String) {
        rule.onAllNodesWithText(label).onFirst().performClick()
        rule.waitForIdle()
    }

    @Test
    fun dashboardRendersWithHonestSubtitle() {
        rule.onAllNodesWithText("Dashboard").onFirst().assertExists()
        rule.onAllNodesWithText("Everything below is read from this tablet's own database.")
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
