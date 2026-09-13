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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
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
fun AccountScreen(accounts: AccountStore, model: SignInModel, onHouseholds: () -> Unit = {}, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val data by accounts.active.collectAsState()
    val selectedWorkspace = data?.selectedWorkspace?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    var records by remember(data) { mutableStateOf<List<RecoveryText>>(emptyList()) }
    var recordings by remember(data) { mutableIntStateOf(0) }
    var ready by remember(data) { mutableStateOf(false) }
    var failure by remember(data) { mutableStateOf(false) }
    var exported by remember(data) { mutableStateOf(false) }
    var importPreview by remember(data) { mutableStateOf<List<RecoveryText>?>(null) }
    var selected by remember(data) { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
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
    BackHandler(onBack = onClose)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.account_title)) },
            navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(24.dp).semantics { testTagsAsResourceId = true },
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val current = data
            val signedIn = current?.identity != null
            Text(stringResource(if (signedIn) R.string.account_signed_in else R.string.account_anonymous),
                style = MaterialTheme.typography.headlineSmall)
            if (signedIn && model.choices.isNotEmpty()) {
                Text(stringResource(R.string.identity_selected,
                    if (current!!.identity!!.subject == "alice") "Alice" else "Bob"))
            }
            if (signedIn) Button(onClick = onHouseholds, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth().testTag("account-households")) {
                Text(stringResource(R.string.households_title))
            }
            if (current != null) key(current.lease.generation) {
                LegacyRecordingsSection(accounts, current, onClose)
            }
            Text(stringResource(R.string.account_local_notice))
            if (accounts.unexpectedFiles) Text(stringResource(R.string.account_installation_reset),
                color = MaterialTheme.colorScheme.error)
            if (ready) Text(stringResource(R.string.account_pending, records.size, recordings))
            Text(stringResource(model.status), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (model.busy || working || !ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure) Text(stringResource(R.string.account_operation_failed), color = MaterialTheme.colorScheme.error)
            if (exported) Text(stringResource(R.string.account_exported))
            if (signedIn || records.isNotEmpty()) {
                Text(stringResource(R.string.account_export_warning))
                val parts = remember(records) { runCatching { RecoveryExport.parts(records) }.getOrDefault(emptyList()) }
                parts.forEachIndexed { index, part ->
                    for (plain in listOf(false, true)) {
                        OutlinedButton(enabled = ready && !working, modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                pendingExport = Triple(current!!, part, plain)
                                export.launch("bun-do-recovery-${index + 1}.${if (plain) "txt" else "json"}")
                            }) {
                            Text(stringResource(if (plain) R.string.account_export_text else R.string.account_export_json, index + 1))
                        }
                    }
                }
                if (parts.isEmpty()) Text(stringResource(R.string.account_operation_failed))
            }
            if (signedIn) {
                OutlinedButton(onClick = { importOwner = current; openRecovery.launch(arrayOf("application/json", "text/plain")) },
                    enabled = !working && !model.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shared_import_file))
                }
                OutlinedButton(onClick = { model.refresh() }, enabled = !model.busy && !working,
                    modifier = Modifier.fillMaxWidth().testTag("account-refresh")) { Text(stringResource(R.string.identity_refresh)) }
                OutlinedButton(onClick = {
                    scope.launch {
                        working = true
                        try { importPreview = withContext(Dispatchers.IO) { accounts.anonymousPreview(current!!) } }
                        catch (_: Exception) { failure = true }
                        finally { working = false }
                    }
                }, enabled = !working, modifier = Modifier.fillMaxWidth().testTag("account-import-preview")) {
                    Text(stringResource(R.string.account_import_preview))
                }
                importPreview?.let { preview ->
                    Text(stringResource(R.string.account_import_notice))
                    preview.forEach { item ->
                        Row {
                            Checkbox(checked = item.source in selected,
                                enabled = InboxLimits.valid(item.title, item.description),
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + item.source else selected - item.source
                                })
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                if (item.description.isNotEmpty()) Text(item.description)
                            }
                        }
                    }
                    Button(enabled = selected.isNotEmpty() && !working, onClick = {
                        scope.launch {
                            working = true
                            try {
                                withContext(Dispatchers.IO) { accounts.importText(current!!, preview.filter { it.source in selected }) }
                                importPreview = null
                                selected = emptySet()
                                records = withContext(Dispatchers.IO) { accounts.recovery(current!!) }
                            } catch (_: Exception) { failure = true }
                            finally { working = false }
                        }
                    }) { Text(stringResource(R.string.account_import_selected)) }
                }
                OutlinedButton(onClick = { pendingAction = { model.signOut() } }, enabled = ready && !working,
                    modifier = Modifier.fillMaxWidth().testTag("account-sign-out")) { Text(stringResource(R.string.identity_sign_out)) }
                TextButton(onClick = { pendingAction = { model.signOut(delete = true) } }, enabled = ready && !working,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_delete_sign_out)) }
            }
            if (!signedIn && model.hasAccount) {
                OutlinedButton(onClick = { model.refresh() }, enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().testTag("account-refresh")) {
                    Text(stringResource(R.string.identity_refresh))
                }
            }
            model.choices.ifEmpty { listOf("") }.forEach { choice ->
                OutlinedButton(enabled = !model.busy && !working && ready,
                    modifier = Modifier.fillMaxWidth().testTag("account-use-${choice.lowercase()}"),
                    onClick = {
                        val action = { model.signIn(activity, choice.ifEmpty { null }) }
                        if (signedIn) pendingAction = action else action()
                    }) {
                    Text(if (choice.isEmpty()) stringResource(R.string.identity_sign_in)
                    else stringResource(R.string.identity_choose, choice))
                }
            }
            if (model.errorCode == "registration_retired") {
                Text(stringResource(R.string.account_registration_retired))
                OutlinedButton(onClick = { pendingAction = { model.refresh(replaceRetired = true) } },
                    enabled = !model.busy) {
                    Text(stringResource(R.string.account_register_again))
                }
            }
            if (selectedWorkspace?.blocked == "REGISTRATION_REPLACEMENT_REQUIRED") {
                Text(stringResource(R.string.shared_registration_recovery))
                OutlinedButton(onClick = {
                    val oldRegistration = data?.registrationId
                    pendingAction = { model.refresh(revokeRegistration = oldRegistration, replaceRetired = true) }
                }, enabled = !model.busy && !working) {
                    Text(stringResource(R.string.account_register_again))
                }
            }
            model.registrationChoices.takeIf { model.errorCode == "registration_limit" }
                .orEmpty().forEachIndexed { index, registration ->
                OutlinedButton(onClick = { pendingAction = { model.refresh(registration) } }, enabled = !model.busy) {
                    Text(stringResource(R.string.account_revoke_registration, index + 1))
                }
            }
        }
    }
    pendingAction?.let { action ->
        AlertDialog(onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.account_confirm_title)) },
            text = { Text(stringResource(R.string.account_pending, records.size, recordings) + "\n\n" +
                stringResource(R.string.account_confirm_body)) },
            confirmButton = { TextButton(onClick = { pendingAction = null; action() }) { Text(stringResource(R.string.account_continue)) } },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.account_cancel)) } })
    }
}
