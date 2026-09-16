package fi.bundo

import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.bundo.data.*
import fi.bundo.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdventureArtworkUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    @Test fun fallback_failure_and_explicit_retry_keep_the_adventure_available() {
        val state = adventureWorkspace(); val root = adventureTask()
        val choice = AdventureSnapshot.read(adventureFixture(state, root)).active!!
        val calls = mutableListOf<Boolean>()
        val evidence = java.io.File(compose.activity.getExternalFilesDir(null), "artwork-live-evidence.jpg")
        val bitmap = if (evidence.isFile) BitmapFactory.decodeFile(evidence.path)
            else BitmapFactory.decodeResource(compose.activity.resources, R.drawable.dojo_garden)
        val load: suspend (AdventureChoice, Boolean) -> ArtworkResult = { _, retry ->
            calls.add(retry); if (retry) ArtworkResult("READY", bitmap) else ArtworkResult("FAILED")
        }
        compose.runOnUiThread { compose.activity.setContent {
            BunDoTheme("light") { Surface { Column(Modifier.safeDrawingPadding()) {
                CompositionLocalProvider(LocalAdventureArtwork provides load) { AdventureArtwork(choice.artwork, choice) }
            } } }
        } }
        compose.onNodeWithTag("adventure-art-dojo-garden").assertIsDisplayed()
        screenshot("artwork-fallback-retry")
        compose.onNodeWithText(compose.activity.getString(R.string.artwork_retry)).performClick()
        compose.waitUntil { calls.size == 2 }
        compose.onNodeWithText(compose.activity.getString(R.string.artwork_retry)).assertDoesNotExist()
        compose.onNodeWithTag("adventure-art-dojo-garden").assertIsDisplayed()
        screenshot("artwork-loaded")
        assertEquals(listOf(false, true), calls)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        try { java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { image.recycle() }
    }
}
