package fi.bundo

import android.os.Bundle
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.CircularProgressIndicator
import fi.bundo.ui.AccountScreen
import fi.bundo.ui.HouseholdScreen
import fi.bundo.household.InvitationLink
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
import fi.bundo.ui.SharedWorkspaceScreen

class MainActivity : AppCompatActivity() {
    private var invitation by mutableStateOf<String?>(null)

    private fun acceptInvitation(intent: Intent?) {
        val value = intent?.dataString ?: return
        if (InvitationLink.parse(value) != null) invitation = value
        // Do not retain an invitation secret in the Activity intent or saved state.
        intent.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptInvitation(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptInvitation(intent)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        val accounts = (application as BunDoApplication).accounts
        setContent {
            val account by accounts.active.collectAsStateWithLifecycle()
            var showAccount by rememberSaveable { mutableStateOf(invitation != null) }
            var showHouseholds by rememberSaveable { mutableStateOf(invitation != null) }
            androidx.compose.runtime.LaunchedEffect(invitation) {
                if (invitation != null) { showAccount = true; showHouseholds = true }
            }
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
                if (showHouseholds && account?.identity != null) {
                    key(account!!.lease.generation) {
                        HouseholdScreen(account!!, signIn, invitation, { invitation = null }) {
                            showHouseholds = false
                        }
                    }
                } else if (showAccount) {
                    AccountScreen(accounts, signIn, onHouseholds = { showHouseholds = true }) { showAccount = false }
                } else if (account == null) {
                    CircularProgressIndicator()
                } else key(account!!.lease.generation) {
                val data = account!!
                val selected by data.selectedWorkspace.collectAsStateWithLifecycle()
                if (selected != null) {
                    SharedWorkspaceScreen(data, selected!!, appearance, {
                        preferences.edit { putString("theme", it) }
                        appearance = it
                    }, { showAccount = true })
                } else {
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
}
