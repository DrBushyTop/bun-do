package fi.bundo.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.bundo.R
import fi.bundo.data.AccountData
import fi.bundo.data.WelcomeStep
import fi.bundo.data.WelcomeStore
import fi.bundo.household.HouseholdModel
import fi.bundo.household.recoverWelcomeHome
import fi.bundo.identity.SignInModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.UUID

@Composable
internal fun WelcomeRoute(store: WelcomeStore, account: AccountData?, signIn: SignInModel, onAccountHelp: () -> Unit) {
    val progress by store.state.collectAsState()
    val storageFailed by store.writeFailed.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model: HouseholdModel? = if (account?.identity != null) viewModel(
        key = "welcome-households-${account.lease.generation}", factory = viewModelFactory {
            initializer { HouseholdModel(account, signIn) }
        }) else null
    val connected = account?.identity != null && account.registrationId != null && signIn.hasAccount
    val defaultName = stringResource(R.string.welcome_default_name)
    var enterFailed by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, connected, signIn.busy) {
        if (connected && !signIn.busy) model?.refresh()
    }
    LaunchedEffect(model, model?.homes, progress.homeId, progress.createId, progress.link, progress.step) {
        if (model != null) {
            val id = recoverWelcomeHome(progress, model.homes)?.id
            if (model.current?.id != id) model.restore(id.orEmpty())
            if (!id.isNullOrEmpty() && progress.step != WelcomeStep.HOME && model.current?.id == id)
                store.accepted(progress, id)
        }
    }
    val actionableStatus = signIn.status in listOf(R.string.identity_failed, R.string.identity_api_unavailable,
        R.string.identity_api_rejected, R.string.identity_registration_failed, R.string.identity_cancelled)
    val message = when {
        storageFailed -> R.string.welcome_save_failed
        enterFailed -> R.string.household_unavailable
        !model?.failure.isNullOrEmpty() -> householdError(model!!.failure)
        actionableStatus -> signIn.status
        else -> null
    }
    val busy = signIn.busy || model?.busy == true
    WelcomeScreen(progress, account?.identity != null, connected, model?.homes.orEmpty(), model?.current,
        busy, message, model?.shareLink != null,
        WelcomeActions(
            back = {
                model?.back()
                when (progress.step) {
                    WelcomeStep.START, WelcomeStep.FAMILY -> store.dismiss()
                    else -> store.update { it.copy(step = WelcomeStep.FAMILY) }
                }
            },
            dismiss = store::dismiss,
            family = { store.update { it.copy(step = WelcomeStep.FAMILY) } },
            create = {
                model?.back()
                store.update { it.copy(step = WelcomeStep.CREATE, name = defaultName, homeId = "", link = "",
                    createId = UUID.randomUUID().toString()) }
            },
            join = {
                model?.back()
                store.update { it.copy(step = WelcomeStep.JOIN, homeId = "", link = "") }
            },
            signIn = { choice ->
                if (signIn.hasAccount && choice == null) signIn.refresh() else signIn.signIn(context as Activity, choice)
            },
            accountHelp = onAccountHelp,
            edit = { value -> store.update { value } },
            submit = {
                val request = progress
                scope.launch {
                    try {
                        // Persist the create request ID before the remote action, so a lost reply can be recovered.
                        store.update { it }
                        store.awaitSaved()
                        val accepted: (fi.bundo.household.Household) -> Unit = { home ->
                            store.accepted(request, home.id)
                        }
                        if (progress.step == WelcomeStep.CREATE) model?.create(request.name.trim(), request.displayName.trim(), request.createId, accepted)
                        else model?.redeem(request.link, request.displayName.trim(), accepted)
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { enterFailed = true }
                }
            },
            open = { home ->
                store.update { it.copy(step = WelcomeStep.HOME, homeId = home.id, link = "") }
                model?.open(home)
            },
            refresh = { enterFailed = false; store.update { it }; model?.refresh() },
            enter = {
                model?.current?.let { home -> enterFailed = false; model.select(home, store::dismiss) }
            },
            invite = { model?.current?.let { model.command("invite", it) } },
            share = {
                model?.shareLink?.let { link ->
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link)
                    }, null))
                }
            },
            approve = { invitation, code -> model?.current?.let { model.command("approve", it, invitation = invitation, code = code) } },
        ), signIn.choices)
}
