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

    override fun onStart() {
        super.onStart()
        (application as BunDoApplication).accounts.active.value?.let {
            fi.bundo.reminders.ReminderWorker.request(this, it)
        }
    }

    private fun acceptInvitation(intent: Intent?) {
        val value = intent?.dataString ?: return
        (application as BunDoApplication).welcome.incoming(value)
        // Do not retain an invitation secret in the Activity intent or saved state.
        intent.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptInvitation(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Native date/time pickers follow the same light-only V1 direction as Compose.
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        acceptInvitation(intent)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        val accounts = (application as BunDoApplication).accounts
        val welcome = (application as BunDoApplication).welcome
        setContent {
            val account by accounts.active.collectAsStateWithLifecycle()
            val progress by welcome.state.collectAsStateWithLifecycle()
            var showAccount by rememberSaveable { mutableStateOf(false) }
            var accountPage by rememberSaveable { mutableStateOf("account") }
            var showHouseholds by rememberSaveable { mutableStateOf(false) }
            val inboxState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            val signIn: SignInModel = viewModel()
            androidx.compose.runtime.LaunchedEffect(account?.identity, signIn.busy) {
                if (!signIn.busy && account != null) welcome.bind(account?.identity)
            }
            androidx.compose.runtime.LaunchedEffect(progress.visible, progress.link) {
                if (progress.visible && progress.link.isNotBlank()) { showAccount = false; showHouseholds = false }
            }
            var appearance by remember { mutableStateOf(preferences.getString("theme", "system")!!) }
            fi.bundo.ui.HouseholdMotionProvider {
            BunDoTheme("light") {
                val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                if (showHouseholds && account?.identity != null) {
                    key(account!!.lease.generation) {
                        val selectedHousehold by account!!.selectedHousehold.collectAsStateWithLifecycle()
                        HouseholdScreen(account!!, signIn, onSetup = {
                            showHouseholds = false; showAccount = false
                            welcome.update { it.copy(visible = true, step = fi.bundo.data.WelcomeStep.FAMILY) }
                        }, initialHouseholdId = selectedHousehold) {
                            showHouseholds = false
                        }
                    }
                } else if (showAccount) {
                    AccountScreen(accounts, signIn, initialPage = accountPage, onSetup = { showAccount = false; welcome.reopen() }, onHouseholds = { showHouseholds = true }) { showAccount = false }
                } else if (progress.visible) {
                    if (welcome.matches(account?.identity))
                        fi.bundo.ui.WelcomeRoute(welcome, account, signIn, onAccountHelp = { accountPage = "account"; showAccount = true })
                    else CircularProgressIndicator()
                } else if (account == null) {
                    CircularProgressIndicator()
                } else key(account!!.lease.generation) {
                inboxState.SaveableStateProvider(account!!.lease.generation) {
                val data = account!!
                val selected by data.selectedWorkspace.collectAsStateWithLifecycle()
                if (selected != null) {
                    SharedWorkspaceScreen(data, selected!!, appearance, {
                        preferences.edit { putString("theme", it) }
                        appearance = it
                    }, { accountPage = "account"; showAccount = true }, onWelcome = welcome::reopen,
                        onReminders = { accountPage = "reminders"; showAccount = true },
                        onRecovery = { accountPage = "recovery"; showAccount = true })
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
                    onAccount = { accountPage = "account"; showAccount = true },
                    onWelcome = welcome::reopen,
                    onReminders = { accountPage = "reminders"; showAccount = true },
                    onRecovery = { accountPage = "recovery"; showAccount = true },
                )
                }
                }
                }
            }
            }
        }
    }
}
