package fi.bundo

import android.content.res.Configuration
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.LegacyRecordingsSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LegacyRecordingUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), InboxDatabase::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(migrations).around(compose)

    private fun fixture(language: String, appearance: String,
        restoration: StateRestorationTester? = null, picker: PendingPicker? = null, action: (AccountStore) -> Unit) {
        val context = compose.activity
        val name = "legacy-ui-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, name).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        migrations.createDatabase(File(root, InboxDatabase.FILE_NAME).path, 3).use {
            it.execSQL("INSERT INTO voice_recordings VALUES (?, ?, ?, 'FAILED', 'INTERRUPTED')",
                arrayOf<Any>(id, now, now + RecordingStore.RETAIN_MILLIS))
        }
        File(root, "anonymous-audio").mkdirs()
        File(root, "anonymous-audio/$id.pcm").writeBytes(ByteArray(640))
        val accounts = AccountStore(context, name)
        try {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val translated = context.createConfigurationContext(config)
            val data = accounts.active.value!!
            val content: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalContext provides translated, LocalConfiguration provides config,
                        LocalActivityResultRegistryOwner provides (picker ?: compose.activity)) {
                        BunDoTheme(appearance) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                                Surface {
                                    Column(Modifier.fillMaxSize().safeDrawingPadding()
                                        .verticalScroll(rememberScrollState()).padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        LegacyRecordingsSection(accounts, data) {}
                                    }
                                }
                            }
                        }
                    }
            }
            if (restoration == null) compose.runOnUiThread { compose.activity.setContent(content = content) }
            else {
                compose.runOnUiThread { compose.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews() }
                restoration.setContent(content)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("legacy-recording").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(translated.getString(R.string.legacy_audio_title)).assertIsDisplayed()
            compose.onNodeWithTag("legacy-recover").assertIsEnabled()
            compose.onNodeWithTag("legacy-export").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("legacy-delete").performScrollTo().assertIsDisplayed()
            screenshot("legacy-$language-$appearance.png")
            action(accounts)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            runBlocking { accounts.close() }
            context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.deleteRecursively() }
            context.deleteDatabase("$name-anonymous.db")
            context.deleteSharedPreferences("$name-legacy-audio")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let {
                it.deleteEntry("bundo.$name.installation")
                it.deleteEntry("bundo.$name.active")
            }
        }
    }

    @Test fun finnishLargeTextRecoveryOpensExistingVoiceControlsWithoutCreatingTask() = fixture("fi", "light") { accounts ->
        compose.onNodeWithTag("legacy-recover").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { accounts.active.value!!.database.recordings().all().size == 1 } }
        // Both installed-model retry and missing-model installation use the existing voice menu.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("voice-type").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voice-type").assertExists()
        assertTrue(runBlocking { accounts.active.value!!.database.inbox().intents().isEmpty() })
        assertTrue(runBlocking { accounts.legacyAudio.preview(accounts.active.value!!).recordings.isEmpty() })
    }

    @Test fun englishDarkDeleteRequiresConfirmationAndKeepsSourceWhenCanceled() = fixture("en", "dark") { accounts ->
        compose.onNodeWithTag("legacy-delete").performClick()
        compose.onNodeWithText("Permanently delete this recording? Export it first if you want to keep it.").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, runBlocking { accounts.legacyAudio.preview(accounts.active.value!!).recordings.size })
        compose.onNodeWithTag("legacy-delete").performClick()
        compose.onAllNodesWithText("Delete recording").onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("legacy-recording").fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { accounts.legacyAudio.preview(accounts.active.value!!).recordings.isEmpty() })
    }

    @Test fun pendingPickerResultSurvivesSavedStateRecreation() {
        val restoration = StateRestorationTester(compose)
        val picker = PendingPicker()
        fixture("en", "light", restoration, picker) { accounts ->
            val destination = File(compose.activity.cacheDir, "legacy-export-${UUID.randomUUID()}.wav")
            try {
                compose.onNodeWithTag("legacy-export").performScrollTo().performClick()
                assertNotNull(picker.request)
                restoration.emulateSavedInstanceStateRestore()
                compose.runOnUiThread { picker.deliver(destination) }
                compose.waitUntil(10_000) { destination.length() == 684L }
                assertEquals("RIFF", String(destination.readBytes(), 0, 4, Charsets.US_ASCII))
                assertEquals(1, runBlocking { accounts.legacyAudio.preview(accounts.active.value!!).recordings.size })
            } finally { destination.delete() }
        }
    }

    @Test fun restoredPickerResultCannotWriteAfterAccountGenerationEnds() {
        val restoration = StateRestorationTester(compose)
        val picker = PendingPicker()
        fixture("en", "light", restoration, picker) { accounts ->
            val destination = File(compose.activity.cacheDir, "legacy-export-${UUID.randomUUID()}.wav").apply { writeText("unchanged") }
            try {
                compose.onNodeWithTag("legacy-export").performScrollTo().performClick()
                restoration.emulateSavedInstanceStateRestore()
                runBlocking { accounts.signOut() }
                compose.runOnUiThread { picker.deliver(destination) }
                compose.waitForIdle()
                assertEquals("unchanged", destination.readText())
                assertEquals(1, runBlocking { accounts.legacyAudio.preview(accounts.active.value!!).recordings.size })
            } finally { destination.delete() }
        }
    }

    @Test fun accountNavigationSurvivesActivityRecreation() {
        compose.onNodeWithTag("account").performClick()
        compose.onNodeWithTag("account-households").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("account-households").assertExists()
    }

    private class PendingPicker : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var request: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { request = requestCode }
        fun deliver(file: File) { dispatchResult(checkNotNull(request), Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
