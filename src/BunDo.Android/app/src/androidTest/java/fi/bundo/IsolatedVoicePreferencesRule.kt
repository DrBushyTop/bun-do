package fi.bundo

import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.speech.VoicePreferences
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Voice fixtures choose their mode instead of inheriting the emulator user's offline-only setting. */
class IsolatedVoicePreferencesRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val preferences = VoicePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
            val localOnly = preferences.localOnly
            val keepAudio = preferences.keepAudio
            val analyze = preferences.analyze
            try {
                preferences.localOnly = false
                preferences.keepAudio = false
                preferences.analyze = true
                base.evaluate()
            } finally {
                preferences.localOnly = localOnly
                preferences.keepAudio = keepAudio
                preferences.analyze = analyze
            }
        }
    }
}
