package fi.bundo

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.InboxApp
import fi.bundo.ui.InboxViewModel

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var appearance by remember { mutableStateOf(preferences.getString("theme", "system")!!) }
            BunDoTheme(appearance) {
                val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                val model: InboxViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        InboxViewModel((application as BunDoApplication).inbox, createSavedStateHandle())
                    }
                })
                val state by model.state.collectAsStateWithLifecycle()
                InboxApp(
                    state = state,
                    model = model,
                    appearance = appearance,
                    onAppearance = {
                        preferences.edit { putString("theme", it) }
                        appearance = it
                    },
                )
            }
        }
    }
}
