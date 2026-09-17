package fi.bundo.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import fi.bundo.R
import fi.bundo.data.AccountData
import fi.bundo.data.AccountStore
import fi.bundo.data.InboxLimits
import fi.bundo.data.RecoveryExport
import fi.bundo.data.RecoveryText
import fi.bundo.identity.SignInModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(accounts: AccountStore, model: SignInModel, onHouseholds: () -> Unit = {}, initialPage: String = "account",
    onSetup: (() -> Unit)? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val data by accounts.active.collectAsState()
    val selectedWorkspace = data?.selectedWorkspace?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    var page by rememberSaveable(data, initialPage) { mutableStateOf(initialPage) }
    var records by remember(data) { mutableStateOf<List<RecoveryText>>(emptyList()) }
    var recordings by remember(data) { mutableIntStateOf(0) }
    var ready by remember(data) { mutableStateOf(false) }
    var failure by remember(data) { mutableStateOf(false) }
    var exported by remember(data) { mutableStateOf(false) }
    var importPreview by remember(data) { mutableStateOf<List<RecoveryText>?>(null) }
    var selected by remember(data) { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<AccountAction?>(null) }
    var pendingExport by remember { mutableStateOf<Triple<AccountData, List<RecoveryText>, Boolean>?>(null) }
    val label = stringResource(R.string.account_inbox)
    var importOwner by remember { mutableStateOf<AccountData?>(null) }
    val openRecovery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val owner = importOwner
        importOwner = null
        if (uri != null && owner != null) scope.launch {
            working = true
            try {
                val texts = withContext(Dispatchers.IO) {
                    owner.lease.access {
                        checkNotNull(context.contentResolver.openInputStream(uri)).use(RecoveryExport::read)
                    }
                }
                if (data === owner && owner.lease.active) { importPreview = texts; selected = emptySet() }
            } catch (_: Exception) { failure = true }
            finally { working = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) scope.launch {
            working = true
            try {
                withContext(Dispatchers.IO) {
                    request.first.lease.access {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                            RecoveryExport.write(it, request.second, label, request.third, request.first.lease)
                        }
                    }
                }
                if (data === request.first) exported = true
            } catch (_: Exception) { failure = true }
            finally { working = false }
        }
    }
    LaunchedEffect(data) {
        pendingAction = null
        val current = data ?: return@LaunchedEffect
        try {
            combine(current.inbox.tasks, current.inbox.drafts, current.recordings.recordings,
                current.database.shared().observeAllIntents(), current.database.shared().observeAllDrafts()) { _, _, audio, _, _ ->
                audio
            }.collect { audio ->
                current.lease.check()
                records = withContext(Dispatchers.IO) { accounts.recovery(current) }
                recordings = audio.size
                ready = true
            }
        } catch (_: Exception) { failure = true }
    }
    val pageScroll = key(page) { rememberScrollState() }
    fun back() { if (page == initialPage) onClose() else page = initialPage }
    fun confirm(title: Int, body: Int, button: Int = title, action: () -> Unit) { pendingAction = AccountAction(title, body, button, action) }
    BackHandler(onBack = ::back)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(when (page) {
            "recovery" -> R.string.account_recovery_details
            "reminders" -> R.string.reminders_title
            else -> R.string.family_account
        })) }, navigationIcon = { TextButton(onClick = ::back) { Text(stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(pageScroll)
            .padding(24.dp).semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val current = data
            val signedIn = current?.identity != null
            val actionableStatus = model.status in listOf(R.string.identity_failed, R.string.identity_api_unavailable,
                R.string.identity_api_rejected, R.string.identity_registration_failed, R.string.identity_cancelled)
            if (model.busy || model.errorCode.isNotEmpty() || actionableStatus)
                Text(stringResource(model.status), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (model.busy || working || !ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure) Text(stringResource(R.string.account_operation_failed), color = MaterialTheme.colorScheme.error)
            if (accounts.unexpectedFiles) Text(stringResource(R.string.account_recovery_needed), color = MaterialTheme.colorScheme.error)
            when (page) {
                "reminders" -> if (current != null) key(current.lease.generation) { ReminderSettingsSection(current) }
                "recovery" -> {
                    Text(stringResource(R.string.account_local_notice), style = MaterialTheme.typography.bodyMedium)
                    if (ready) {
                        Text(stringResource(R.string.account_pending, records.size, recordings))
                        if (records.isEmpty()) Text(stringResource(R.string.recovery_empty))
                        else Button(onClick = { importPreview = records; selected = emptySet() }, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("recovery-review")) { Text(stringResource(R.string.recovery_review)) }
                    }
                    importPreview?.let { preview ->
                        Text(stringResource(R.string.account_import_notice))
                        if (preview.isEmpty()) Text(stringResource(R.string.recovery_empty))
                        preview.forEach { item ->
                            Row {
                                val valid = InboxLimits.valid(item.title, item.description)
                                Checkbox(checked = item.source in selected, enabled = valid && !working,
                                    modifier = Modifier.semantics { contentDescription = item.title },
                                    onCheckedChange = { checked -> selected = if (checked) selected + item.source else selected - item.source })
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                                    if (item.description.isNotEmpty()) Text(item.description)
                                    item.workspaceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (!valid) Text(stringResource(R.string.recovery_text_invalid), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Button(enabled = selected.isNotEmpty() && !working && current != null, onClick = {
                            val owner = current!!
                            scope.launch {
                                working = true
                                try {
                                    withContext(Dispatchers.IO) { accounts.importText(owner, preview.filter { it.source in selected }) }
                                    if (data === owner && owner.lease.active) { importPreview = null; selected = emptySet() }
                                } catch (_: Exception) { failure = true }
                                finally { working = false }
                            }
                        }, modifier = Modifier.testTag("recovery-copy")) { Text(stringResource(R.string.account_import_selected)) }
                        TextButton(onClick = { importPreview = null; selected = emptySet() }, enabled = !working) { Text(stringResource(R.string.back)) }
                    }
                    if (exported) Text(stringResource(R.string.account_exported))
                    HelpDisclosure(stringResource(R.string.recovery_backup), Modifier.testTag("recovery-backup")) {
                        Text(stringResource(R.string.account_export_warning))
                        val parts = remember(records) { runCatching { RecoveryExport.parts(records) }.getOrDefault(emptyList()) }
                        parts.forEachIndexed { index, part ->
                            for (plain in listOf(false, true)) {
                                OutlinedButton(enabled = ready && !working && current != null, modifier = Modifier.fillMaxWidth(), onClick = {
                                    pendingExport = Triple(current!!, part, plain)
                                    export.launch("bun-do-recovery-${index + 1}.${if (plain) "txt" else "json"}")
                                }) { Text(stringResource(if (plain) R.string.account_export_text else R.string.account_export_json, index + 1)) }
                            }
                        }
                        Text(stringResource(R.string.recovery_formats), style = MaterialTheme.typography.bodySmall)
                    }
                    if (signedIn) {
                        SettingsNavigationRow(stringResource(R.string.shared_import_file), {
                            importOwner = current; openRecovery.launch(arrayOf("application/json", "text/plain"))
                        }, enabled = !working && !model.busy)
                        SettingsNavigationRow(stringResource(R.string.account_import_preview), {
                            val owner = current!!
                            scope.launch {
                                working = true
                                try {
                                    val preview = withContext(Dispatchers.IO) { accounts.anonymousPreview(owner) }
                                    if (data === owner && owner.lease.active) { importPreview = preview; selected = emptySet() }
                                } catch (_: Exception) { failure = true }
                                finally { working = false }
                            }
                        }, Modifier.testTag("account-import-preview"), enabled = !working)
                    }
                    if (current != null) HelpDisclosure(stringResource(R.string.recovery_recordings)) {
                        key(current.lease.generation) { LegacyRecordingsSection(accounts, current, onClose) }
                    }
                }
                else -> {
                    Text(selectedWorkspace?.name ?: stringResource(if (signedIn) R.string.account_signed_in else R.string.account_anonymous),
                        style = MaterialTheme.typography.headlineSmall)
                    if (signedIn && model.choices.isNotEmpty()) Text(stringResource(R.string.identity_selected,
                        if (current!!.identity!!.subject == "alice") "Alice" else "Bob"))
                    val membership = selectedWorkspace?.membership?.let { org.json.JSONObject(it) }
                    if (signedIn && membership != null && membership.optString("me").isNotBlank() && membership.optString("ownerId") == membership.optString("me"))
                        Button(onClick = onHouseholds, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("account-invite")) {
                            Text(stringResource(R.string.welcome_invite))
                        }
                    if (signedIn) SettingsNavigationRow(stringResource(R.string.family_members_invites), onHouseholds,
                        Modifier.testTag("account-households"), icon = Icons.Outlined.Home, enabled = !model.busy)
                    if (onSetup != null) SettingsNavigationRow(stringResource(R.string.welcome_setup), onSetup,
                        Modifier.testTag("welcome-settings"), icon = Icons.Outlined.Add, enabled = !model.busy)
                    SettingsNavigationRow(stringResource(R.string.account_recovery_details), { page = "recovery" },
                        Modifier.testTag("account-recovery-details"), icon = Icons.Outlined.Refresh)
                    if (signedIn) SettingsNavigationRow(stringResource(R.string.account_reminder_settings), { page = "reminders" },
                        Modifier.testTag("account-reminders"), icon = Icons.Outlined.Notifications)
                    @Composable fun signInChoices() {
                        model.choices.ifEmpty { listOf("") }.forEach { choice ->
                            OutlinedButton(enabled = !model.busy && !working && ready,
                                modifier = Modifier.fillMaxWidth().testTag("account-use-${choice.lowercase()}"), onClick = {
                                    val action = { model.signIn(activity, choice.ifEmpty { null }) }
                                    if (signedIn) confirm(R.string.account_switch, R.string.account_switch_warning, action = action) else action()
                                }) { Text(if (choice.isEmpty()) stringResource(R.string.identity_sign_in) else stringResource(R.string.identity_choose, choice)) }
                        }
                    }
                    if (signedIn) HelpDisclosure(stringResource(R.string.account_switch)) { signInChoices() } else signInChoices()
                    if (signedIn) {
                        HelpDisclosure(stringResource(R.string.account_diagnostics), Modifier.testTag("account-diagnostics")) {
                            Text(stringResource(model.status))
                            if (accounts.unexpectedFiles) Text(stringResource(R.string.account_installation_reset))
                            OutlinedButton(onClick = { model.refresh() }, enabled = !model.busy && !working,
                                modifier = Modifier.fillMaxWidth().testTag("account-refresh")) { Text(stringResource(R.string.identity_refresh)) }
                        }
                        OutlinedButton(onClick = { confirm(R.string.identity_sign_out, R.string.account_sign_out_warning) { model.signOut() } },
                            enabled = ready && !working && !model.busy, modifier = Modifier.fillMaxWidth().testTag("account-sign-out")) { Text(stringResource(R.string.identity_sign_out)) }
                        HelpDisclosure(stringResource(R.string.account_remove_device_data), Modifier.testTag("account-delete-details")) {
                            Text(stringResource(R.string.account_delete_warning))
                            OutlinedButton(onClick = { confirm(R.string.account_delete_sign_out, R.string.account_delete_warning, R.string.account_delete_confirm) { model.signOut(delete = true) } },
                                enabled = ready && !working && !model.busy, modifier = Modifier.fillMaxWidth().testTag("account-delete")) {
                                Text(stringResource(R.string.account_delete_sign_out), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (!signedIn && model.hasAccount) OutlinedButton(onClick = { model.refresh() }, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth().testTag("account-refresh")) { Text(stringResource(R.string.identity_refresh)) }
            if (model.errorCode == "registration_retired") {
                Text(stringResource(R.string.account_registration_retired))
                OutlinedButton(onClick = { confirm(R.string.account_register_again, R.string.account_registration_warning) { model.refresh(replaceRetired = true) } },
                    enabled = !model.busy) { Text(stringResource(R.string.account_register_again)) }
            }
            if (selectedWorkspace?.blocked == "REGISTRATION_REPLACEMENT_REQUIRED") {
                Text(stringResource(R.string.shared_registration_recovery))
                OutlinedButton(onClick = {
                    val oldRegistration = data?.registrationId
                    confirm(R.string.account_register_again, R.string.account_registration_warning) { model.refresh(revokeRegistration = oldRegistration, replaceRetired = true) }
                }, enabled = !model.busy && !working) { Text(stringResource(R.string.account_register_again)) }
            }
            model.registrationChoices.takeIf { model.errorCode == "registration_limit" }.orEmpty().forEachIndexed { index, registration ->
                OutlinedButton(onClick = { confirm(R.string.account_revoke_title, R.string.account_revoke_warning) { model.refresh(registration) } }, enabled = !model.busy) {
                    Text(stringResource(R.string.account_revoke_registration, index + 1))
                }
            }
        }
    }
    pendingAction?.let { action ->
        Dialog(onDismissRequest = { pendingAction = null }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(action.title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.account_pending, records.size, recordings) + "\n\n" + stringResource(action.body))
                    }
                    // Explicit rows keep multiline actions separate at Android's largest font size.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pendingAction = null; action.run() }, enabled = !model.busy && !working,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(action.button)) }
                        TextButton(onClick = { pendingAction = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.account_cancel))
                        }
                    }
                }
            }
        }
    }
}

private data class AccountAction(val title: Int, val body: Int, val button: Int, val run: () -> Unit)
