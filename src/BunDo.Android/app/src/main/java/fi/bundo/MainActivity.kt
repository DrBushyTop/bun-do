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
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.CircularProgressIndicator
import fi.bundo.ui.AccountScreen
import fi.bundo.identity.SignInModel
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
        val accounts = (application as BunDoApplication).accounts
        setContent {
            val account by accounts.active.collectAsStateWithLifecycle()
            var showAccount by remember { mutableStateOf(false) }
            val signIn: SignInModel = viewModel()
            var appearance by remember { mutableStateOf(preferences.getString("theme", "system")!!) }
            BunDoTheme(appearance) {
                val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                if (showAccount) {
                    AccountScreen(accounts, signIn) { showAccount = false }
                } else if (account == null) {
                    CircularProgressIndicator()
                } else key(account!!.lease.generation) {
                val data = account!!
                val model: InboxViewModel = viewModel(key = data.lease.generation, factory = viewModelFactory {
                    initializer {
                        InboxViewModel(data.inbox, createSavedStateHandle())
                    }
                })
                DisposableEffect(model) { onDispose { if (!data.lease.active) model.hide() } }
                val state by model.state.collectAsStateWithLifecycle()
                InboxApp(
                    state = state,
                    model = model,
                    appearance = appearance,
                    onAppearance = {
                        preferences.edit { putString("theme", it) }
                        appearance = it
                    },
                    voice = data.voice,
                    onAccount = { showAccount = true },
                )
                }
            }
        }
    }
}
