package fi.bundo.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.bundo.R
import fi.bundo.data.AccountData
import fi.bundo.household.Household
import fi.bundo.household.HouseholdModel
import fi.bundo.identity.SignInModel
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(account: AccountData, signIn: SignInModel, incomingLink: String?,
    onLinkConsumed: () -> Unit, onClose: () -> Unit) {
    val model: HouseholdModel = viewModel(key = "households-${account.lease.generation}", factory = viewModelFactory {
        initializer { HouseholdModel(account, signIn) }
    })
    val context = LocalContext.current
    val selected by account.selectedHousehold.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf(incomingLink.orEmpty()) }
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(incomingLink != null) }
    var confirm by remember { mutableStateOf<Pair<Int, () -> Unit>?>(null) }
    LaunchedEffect(incomingLink) {
        incomingLink?.let { link = it; showJoin = true; model.back(); onLinkConsumed() }
    }
    LaunchedEffect(model, signIn.busy) {
        if (!signIn.busy && account.registrationId != null) model.refresh()
    }
    BackHandler { if (model.current != null) model.back() else onClose() }
    val home = model.current
    Scaffold(topBar = {
        TopAppBar(title = { Text(home?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.households_title),
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = { if (home != null) model.back() else onClose() }) {
                Text(stringResource(R.string.household_back))
            } }, actions = { TextButton(onClick = model::refresh, enabled = !model.busy) {
                Text(stringResource(R.string.household_refresh))
            } })
    }, modifier = Modifier.semantics { testTagsAsResourceId = true }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(24.dp).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (account.registrationId == null) Text(stringResource(R.string.household_sign_in_required))
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.failure.isNotEmpty()) Text(stringResource(householdError(model.failure)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("household-error"))
            if (home == null) {
                model.homes.forEach { item ->
                    OutlinedButton(onClick = { model.open(item) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(item.name.takeIf { it.isNotBlank() } ?: stringResource(R.string.household_pending))
                    }
                }
                if (model.homes.isEmpty()) Text(stringResource(R.string.household_intro))
                Button(onClick = { showCreate = !showCreate; showJoin = false }, enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().testTag("household-create")) { Text(stringResource(R.string.household_create)) }
                if (showCreate || showJoin) OutlinedTextField(displayName, { if (it.length <= 60) displayName = it },
                    label = { Text(stringResource(R.string.household_your_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("household-your-name"))
                if (showCreate) {
                    OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text(stringResource(R.string.household_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("household-name"), singleLine = true)
                    Button(onClick = { model.create(name, displayName) }, enabled = name.isNotBlank() && displayName.isNotBlank() && !model.busy,
                        modifier = Modifier.testTag("household-create-confirm")) { Text(stringResource(R.string.household_create)) }
                }
                OutlinedButton(onClick = { showJoin = !showJoin; showCreate = false }, enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().testTag("household-join")) { Text(stringResource(R.string.household_join)) }
                if (showJoin) {
                    OutlinedTextField(link, { if (it.length <= 512) link = it },
                        label = { Text(stringResource(R.string.household_link)) }, modifier = Modifier.fillMaxWidth()
                            .testTag("household-link"), maxLines = 4)
                    Text(stringResource(R.string.household_join_explanation))
                    Button(onClick = { model.redeem(link, displayName) }, enabled = link.isNotBlank() && displayName.isNotBlank() && !model.busy,
                        modifier = Modifier.testTag("household-join-confirm")) { Text(stringResource(R.string.household_request_join)) }
                }
            } else {
                if (home.deleted) {
                    Text(stringResource(R.string.household_deleted))
                } else if (home.active) {
                    if (selected == home.id) Text(stringResource(R.string.household_selected))
                    else Button(onClick = { model.select(home) }, enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth().testTag("household-select")) {
                        Text(stringResource(R.string.household_select))
                    }
                    Text(stringResource(R.string.household_inbox_separate), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.household_members), style = MaterialTheme.typography.titleLarge)
                    home.members.forEach { member ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val label = if (member.id == home.me) stringResource(R.string.household_you)
                                else member.displayName.ifBlank { stringResource(R.string.household_member, member.id.take(8)) }
                            Text(label + if (member.owner) " · " + stringResource(R.string.household_owner) else "")
                            if (home.owner && member.id != home.me) {
                                TextButton(onClick = { confirm = R.string.household_remove_warning to {
                                    model.command("remove", home, member = member)
                                } }, enabled = !model.busy, modifier = Modifier.testTag("household-remove-${member.id.take(8)}")) {
                                    Text(stringResource(R.string.household_remove))
                                }
                                TextButton(onClick = { confirm = R.string.household_transfer_warning to {
                                    model.command("transfer", home, member = member)
                                } }, enabled = !model.busy,
                                    modifier = Modifier.testTag("household-transfer-${member.id.take(8)}")) {
                                    Text(stringResource(R.string.household_transfer))
                                }
                            }
                        }
                    }
                    if (home.owner) {
                        HorizontalDivider()
                        Button(onClick = { model.command("invite", home) }, enabled = !model.busy,
                            modifier = Modifier.fillMaxWidth().testTag("household-invite")) { Text(stringResource(R.string.household_invite)) }
                        model.shareLink?.let { invitation ->
                            Text(stringResource(R.string.household_share_notice))
                            Button(onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, invitation)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }, modifier = Modifier.fillMaxWidth().testTag("household-share")) { Text(stringResource(R.string.household_share)) }
                        }
                    }
                }
                if (!home.deleted) home.invitations.forEachIndexed { index, invitation ->
                    HorizontalDivider()
                    Text(stringResource(R.string.household_invitation_number, index + 1), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (invitation.phase) {
                        "Pending" -> R.string.household_pending
                        "Approved" -> R.string.household_approved
                        "Cancelled" -> R.string.household_cancelled
                        "Expired" -> R.string.household_expired
                        else -> R.string.household_invitation_waiting
                    }))
                    if (invitation.phase == "Pending") {
                        if (home.owner) Text(invitation.candidateName, style = MaterialTheme.typography.titleMedium)
                        Text(invitation.code, style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.testTag("household-code"))
                        Text(stringResource(if (home.owner) R.string.household_owner_match else R.string.household_candidate_match))
                        if (home.owner) {
                            var code by remember(invitation.id) { mutableStateOf("") }
                            OutlinedTextField(code, { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
                                label = { Text(stringResource(R.string.household_confirm_code)) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("household-confirm-code"))
                            Button(onClick = { model.command("approve", home, invitation = invitation, code = code) },
                                enabled = code.length == 6 && !model.busy, modifier = Modifier.testTag("household-approve")) {
                                Text(stringResource(R.string.household_approve))
                            }
                        }
                    }
                    if (invitation.phase in listOf("Issued", "Pending")) {
                        val expiry = runCatching { OffsetDateTime.parse(invitation.expiresAt)
                            .atZoneSameInstant(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) }.getOrDefault("")
                        Text(stringResource(R.string.household_expires, expiry), style = MaterialTheme.typography.bodySmall)
                        if (home.owner) TextButton(onClick = { model.command("cancel", home, invitation = invitation) },
                            enabled = !model.busy, modifier = Modifier.testTag("household-cancel-invite")) {
                            Text(stringResource(R.string.household_cancel_invitation))
                        }
                    }
                }
                if (home.active) {
                    HorizontalDivider()
                    if (home.owner) TextButton(onClick = { confirm = R.string.household_delete_warning to {
                        model.command("delete", home)
                    } }, enabled = !model.busy, modifier = Modifier.testTag("household-delete")) {
                        Text(stringResource(R.string.household_delete), color = MaterialTheme.colorScheme.error)
                    } else TextButton(onClick = { confirm = R.string.household_leave_warning to {
                        model.command("leave", home, member = home.members.single { it.id == home.me })
                    } }, enabled = !model.busy, modifier = Modifier.testTag("household-leave")) { Text(stringResource(R.string.household_leave)) }
                }
            }
        }
    }
    confirm?.let { pending ->
        AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { confirm = null }, title = { Text(stringResource(R.string.household_confirm)) },
            text = { Text(stringResource(pending.first)) }, confirmButton = {
                TextButton(onClick = { confirm = null; pending.second() }, modifier = Modifier.testTag("household-action-confirm")) {
                    Text(stringResource(R.string.household_continue))
                }
            }, dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.household_cancel)) } })
    }
}

internal fun householdError(code: String): Int = when (code) {
    "STORAGE_FULL" -> R.string.household_storage_full
    "INVALID_LINK" -> R.string.household_invalid_link
    "INVITATION_UNAVAILABLE", "INVITATION_ALREADY_USED" -> R.string.household_invitation_unavailable
    "CONFIRMATION_MISMATCH" -> R.string.household_code_mismatch
    "REDEMPTION_LIMIT" -> R.string.household_rate_limit
    "MEMBER_LIMIT" -> R.string.household_member_limit
    "INVITATION_LIMIT" -> R.string.household_invite_limit
    "OWNER_MUST_TRANSFER" -> R.string.household_transfer_first
    "FORBIDDEN" -> R.string.household_access_lost
    "VERSION_CONFLICT", "EPOCH_CHANGED" -> R.string.household_changed
    "REGISTRATION_RETIRED", "SIGN_IN_REQUIRED" -> R.string.household_sign_in_required
    else -> R.string.household_unavailable
}
