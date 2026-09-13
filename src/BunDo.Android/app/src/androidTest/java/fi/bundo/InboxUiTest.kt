package fi.bundo

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import fi.bundo.data.EditorDraft
import kotlinx.coroutines.runBlocking
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InboxUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    @Test fun landscapeQueueUsesAvailableWidthBeforeSelectingTask() {
        val previousOrientation = compose.activity.requestedOrientation
        try {
            compose.runOnUiThread {
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitForIdle()
            val queueBounds = compose.onNodeWithTag("queue").getUnclippedBoundsInRoot()
            assertTrue(
                "An unselected queue must not reserve a blank detail pane",
                queueBounds.right.value - queueBounds.left.value > 600,
            )
            compose.onNodeWithText(compose.activity.getString(R.string.local_only)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.local_explanation)).assertDoesNotExist()
            compose.onNodeWithTag("capture").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = previousOrientation }
        }
    }

    @Test fun draftSurvivesRecreationThenTaskCanBeEdited() {
        val suffix = UUID.randomUUID().toString().take(8)
        val title = "Järjestä varasto $suffix"
        compose.waitUntil(10_000) {
            compose.onAllNodes(
                androidx.compose.ui.test.hasTestTag("capture") and androidx.compose.ui.test.isEnabled(),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("capture").performClick()
        waitForTag("title")
        compose.onNodeWithTag("title").performTextReplacement(title)
        compose.onNodeWithTag("description").performTextReplacement("Keep this English description")
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("draft-saved")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        waitForTag("title")
        compose.onNodeWithTag("title").assertTextContains(title)
        compose.onNodeWithTag("back").performClick()
        waitForTag("capture")
        compose.onNodeWithTag("capture").performClick()
        waitForTag("title")
        compose.onNodeWithTag("title").assertTextContains(title)
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(title)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("title")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithTag("edit").performClick()
        waitForTag("title")
        compose.onNodeWithTag("title").performTextReplacement("Järjestä varaston hyllyt")
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("title")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Järjestä varaston hyllyt").assertIsDisplayed()
        compose.onNodeWithText("Keep this English description").performScrollTo().assertIsDisplayed()
    }

    @Test fun returningFromSettingsKeepsQueueScrollPosition() {
        val suffix = UUID.randomUUID().toString().take(8)
        runBlocking {
            val inbox = (compose.activity.application as BunDoApplication).inbox
            repeat(30) { inbox.commit(EditorDraft("new", "Scroll fixture $suffix item $it")) }
        }
        compose.onNodeWithTag("queue").performScrollToIndex(20)
        val visible = compose.onAllNodes(
            androidx.compose.ui.test.hasText("Scroll fixture $suffix", substring = true),
        ).fetchSemanticsNodes().first().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText(visible).assertIsDisplayed()
    }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
}
