package fi.bundo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.RefreshPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshPageUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private var refreshing by mutableStateOf(false)
    private var calls = 0
    private fun screen(enabled: Boolean = true, acceptRefresh: Boolean = true) {
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") {
            RefreshPage("Refresh this page", refreshing, enabled, { calls++; refreshing = acceptRefresh }, Modifier.testTag("refresh-test")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Text("Cached content") }
            }
        } } }
    }
    @Test fun automaticReadIsQuietButPullShowsProgressWithoutHidingContent() {
        refreshing = true; screen()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        compose.runOnIdle { refreshing = false }
        compose.onNodeWithTag("refresh-test").performTouchInput { swipeDown(startY = height * .15f, endY = height * .85f, durationMillis = 600) }
        compose.waitUntil(5_000) { calls == 1 }
        compose.onNodeWithText("Cached content").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }
    private fun assertRefreshAction(expected: Boolean) {
        val action = compose.onNodeWithTag("refresh-test").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        compose.runOnIdle { assertEquals(expected, action.action()) }
    }
    @Test fun ignoredManualRefreshDoesNotTurnNextAutomaticReadIntoVisibleLoading() {
        screen(acceptRefresh = false)
        assertRefreshAction(true)
        compose.waitForIdle()
        compose.runOnIdle { refreshing = true }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }
    @Test fun accessibilityRefreshUsesSameActionAndHonorsDisabledState() {
        screen()
        assertRefreshAction(true)
        assertEquals(1, calls)
        assertRefreshAction(false)
        assertEquals(1, calls)
        compose.runOnIdle { refreshing = false }
        screen(enabled = false)
        assertRefreshAction(false)
        assertEquals(1, calls)
    }
}
