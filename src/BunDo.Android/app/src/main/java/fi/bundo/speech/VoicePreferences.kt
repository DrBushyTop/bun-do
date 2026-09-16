package fi.bundo.speech

import android.content.Context
import androidx.core.content.edit

/** Device preferences contain no audio, transcript or account identifiers. */
class VoicePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("voice-preferences", Context.MODE_PRIVATE)
    var localOnly: Boolean
        get() = preferences.getBoolean("local-only", false)
        set(value) { preferences.edit { putBoolean("local-only", value) } }
    var keepAudio: Boolean
        get() = preferences.getBoolean("keep-audio", false)
        set(value) { preferences.edit { putBoolean("keep-audio", value) } }
    var analyze: Boolean
        get() = preferences.getBoolean("analyze", true)
        set(value) { preferences.edit { putBoolean("analyze", value) } }
}
