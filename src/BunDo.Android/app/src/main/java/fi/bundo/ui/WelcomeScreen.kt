package fi.bundo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.WelcomeProgress
import fi.bundo.data.WelcomeStep
import fi.bundo.household.Household
import fi.bundo.household.HouseholdInvitation
import fi.bundo.household.InvitationLink

internal class WelcomeActions(
    val back: () -> Unit,
    val dismiss: () -> Unit,
    val family: () -> Unit,
    val create: () -> Unit,
    val join: () -> Unit,
    val signIn: (String?) -> Unit,
    val accountHelp: () -> Unit,
    val edit: (WelcomeProgress) -> Unit,
    val submit: () -> Unit,
    val open: (Household) -> Unit,
    val refresh: () -> Unit,
    val enter: () -> Unit,
    val invite: () -> Unit,
    val share: () -> Unit,
    val approve: (HouseholdInvitation, String) -> Unit,
)

/** The same form renders real account operations and deterministic native test fixtures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeScreen(
    progress: WelcomeProgress,
    signedIn: Boolean,
    connectedAccount: Boolean,
    homes: List<Household>,
    home: Household?,
    busy: Boolean,
    message: Int?,
    hasShareLink: Boolean,
    actions: WelcomeActions,
    signInChoices: List<String> = emptyList(),
) {
    var familyChoice by rememberSaveable { mutableStateOf("create") }
    val needsSignIn = progress.step in listOf(WelcomeStep.CREATE, WelcomeStep.JOIN, WelcomeStep.HOME) && !connectedAccount
    val pending = home?.invitations?.firstOrNull { it.phase == "Pending" }
    val title = when {
        needsSignIn -> R.string.welcome_sign_in_title
        progress.step == WelcomeStep.START -> R.string.welcome_title
        progress.step == WelcomeStep.FAMILY -> R.string.welcome_family_title
        progress.step == WelcomeStep.CREATE -> R.string.welcome_create_title
        progress.step == WelcomeStep.JOIN -> R.string.welcome_join_title
        home?.active == true && !home.deleted -> R.string.welcome_ready_title
        pending != null -> R.string.welcome_wait_title
        else -> R.string.welcome_invitation_title
    }
    BackHandler(enabled = !busy, onBack = actions.back)
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(title = {}, navigationIcon = {
                if (progress.step != WelcomeStep.START) TextButton(onClick = actions.back, enabled = !busy) {
                    Text(stringResource(R.string.back))
                }
            })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val art = when {
                    progress.step == WelcomeStep.START -> R.drawable.welcome_notebook
                    progress.step == WelcomeStep.HOME && home?.active == true -> R.drawable.welcome_together
                    else -> R.drawable.welcome_gate
                }
                if (LocalDensity.current.fontScale < 1.8f) Image(
                    painterResource(art), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).height(200.dp),
                )
                Text(stringResource(title), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() }.testTag("welcome-title"))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message != null) Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("welcome-error"))
                if (message == R.string.welcome_save_failed) TextButton(onClick = actions.refresh, enabled = !busy) {
                    Text(stringResource(R.string.retry))
                }
                when {
                    needsSignIn -> {
                        Text(stringResource(R.string.welcome_sign_in_body))
                        signInChoices.ifEmpty { listOf("") }.forEach { choice ->
                            WelcomeButton(if (choice.isEmpty()) stringResource(R.string.welcome_sign_in)
                                else stringResource(R.string.identity_choose, choice),
                                "welcome-sign-in-${choice.lowercase()}", !busy) { actions.signIn(choice.ifEmpty { null }) }
                        }
                        TextButton(onClick = actions.accountHelp, enabled = !busy) { Text(stringResource(R.string.welcome_sign_in_help)) }
                    }
                    progress.step == WelcomeStep.START -> {
                        Text(stringResource(R.string.welcome_body))
                        WelcomeButton(stringResource(R.string.welcome_local), "welcome-local", !busy, actions.dismiss)
                        Text(stringResource(R.string.welcome_local_body), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = actions.family, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("welcome-family")) {
                            Text(stringResource(R.string.welcome_family))
                        }
                    }
                    progress.step == WelcomeStep.FAMILY -> {
                        Text(stringResource(R.string.welcome_family_body))
                        homes.filter { !it.deleted }.forEach { item ->
                            OutlinedButton(onClick = { actions.open(item) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("welcome-home-${item.id}")) {
                                Text(item.name.ifBlank { stringResource(R.string.welcome_wait_title) })
                            }
                        }
                        Column(Modifier.selectableGroup()) {
                            SettingChoiceRow(stringResource(R.string.welcome_create), familyChoice == "create", { familyChoice = "create" },
                                Modifier.testTag("welcome-create"), enabled = !busy)
                            SettingChoiceRow(stringResource(R.string.welcome_join), familyChoice == "join", { familyChoice = "join" },
                                Modifier.testTag("welcome-join"), enabled = !busy)
                        }
                        WelcomeButton(stringResource(R.string.continue_action), "welcome-continue", !busy) {
                            if (familyChoice == "create") actions.create() else actions.join()
                        }
                    }
                    progress.step == WelcomeStep.CREATE || progress.step == WelcomeStep.JOIN -> {
                        Text(stringResource(if (progress.step == WelcomeStep.CREATE) R.string.welcome_create_body else R.string.welcome_join_body))
                        if (progress.step == WelcomeStep.CREATE) OutlinedTextField(
                            progress.name, { if (it.length <= 80) actions.edit(progress.copy(name = it)) },
                            label = { Text(stringResource(R.string.welcome_family_name)) }, singleLine = true,
                            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("welcome-name"))
                        OutlinedTextField(
                            progress.displayName, { if (it.length <= 60) actions.edit(progress.copy(displayName = it)) },
                            label = { Text(stringResource(R.string.household_your_name)) }, singleLine = true,
                            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("welcome-your-name"))
                        if (progress.step == WelcomeStep.JOIN) OutlinedTextField(
                            progress.link, { if (it.length <= 512) actions.edit(progress.copy(link = it)) },
                            label = { Text(stringResource(R.string.household_link)) }, maxLines = 4,
                            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("welcome-link"))
                        val valid = progress.displayName.isNotBlank() &&
                            if (progress.step == WelcomeStep.CREATE) progress.name.isNotBlank() else InvitationLink.parse(progress.link) != null
                        if (progress.step == WelcomeStep.JOIN && progress.link.isNotBlank() && InvitationLink.parse(progress.link) == null)
                            Text(stringResource(R.string.household_invalid_link), color = MaterialTheme.colorScheme.error)
                        WelcomeButton(stringResource(if (progress.step == WelcomeStep.CREATE) R.string.welcome_create else R.string.household_request_join),
                            "welcome-submit", valid && !busy, actions.submit)
                    }
                    progress.step == WelcomeStep.HOME -> {
                        if (home?.active == true && !home.deleted) {
                            Text(home.name, style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.welcome_ready_body))
                            WelcomeButton(stringResource(R.string.welcome_start), "welcome-enter", !busy, actions.enter)
                            if (home.owner) {
                                OutlinedButton(onClick = if (hasShareLink) actions.share else actions.invite, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("welcome-invite")) {
                                    Text(stringResource(if (hasShareLink) R.string.household_share else R.string.welcome_invite))
                                }
                                home.invitations.filter { it.phase == "Pending" }.forEach { invitation ->
                                    HorizontalDivider()
                                    Text(stringResource(R.string.welcome_approval, invitation.candidateName), style = MaterialTheme.typography.titleMedium)
                                    var code by remember(invitation.id) { mutableStateOf("") }
                                    Text(invitation.code, style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(R.string.household_owner_match))
                                    OutlinedTextField(code, { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
                                        label = { Text(stringResource(R.string.household_confirm_code)) }, singleLine = true,
                                        enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("welcome-confirm-${invitation.id}"))
                                    WelcomeButton(stringResource(R.string.household_approve), "welcome-approve-${invitation.id}",
                                        code.length == 6 && !busy) { actions.approve(invitation, code) }
                                }
                            }
                        } else {
                            if (pending != null && home?.deleted == false) {
                                Text(stringResource(R.string.welcome_wait_body))
                                Text(pending.code, style = MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.testTag("welcome-code"))
                                Text(stringResource(R.string.welcome_wait_hint))
                            } else if (!busy) Text(stringResource(R.string.welcome_invitation_unavailable))
                            WelcomeButton(stringResource(R.string.welcome_check), "welcome-refresh", !busy, actions.refresh)
                            if (pending == null) TextButton(onClick = actions.join, enabled = !busy) {
                                Text(stringResource(R.string.welcome_new_invitation))
                            }
                        }
                    }
                }
                if (progress.step != WelcomeStep.START) TextButton(onClick = actions.dismiss, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("welcome-later")) {
                    Text(stringResource(if (signedIn) R.string.welcome_later else R.string.welcome_local))
                }
            }
        }
    }
}

@Composable
private fun WelcomeButton(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(label) }
}
