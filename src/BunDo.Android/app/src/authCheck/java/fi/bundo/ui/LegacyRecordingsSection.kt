package fi.bundo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.AccountData
import fi.bundo.data.AccountStore
import fi.bundo.data.LegacyRecordings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyRecordingsSection(accounts: AccountStore, data: AccountData, onSaved: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
        LocalConfiguration.current.locales[0])
    val scope = rememberCoroutineScope()
    var reviewVoice by rememberSaveable { mutableStateOf(false) }
    var preview by remember(data.lease.generation) { mutableStateOf<LegacyRecordings?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember(data.lease.generation) { mutableStateOf(false) }
    var showVoice by remember(data.lease.generation) { mutableStateOf(false) }
    var deleting by remember(data.lease.generation) { mutableStateOf<String?>(null) }
    var exportSource by rememberSaveable { mutableStateOf<String?>(null) }
    var exportOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var exportGeneration by rememberSaveable { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                val refreshed = withContext(Dispatchers.IO) { accounts.legacyAudio.preview(data) }
                data.lease.check()
                preview = refreshed
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failure = true }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val source = exportSource
        val owner = accounts.matching(exportOwner, exportGeneration)
        exportSource = null
        exportOwner = null
        exportGeneration = null
        if (uri != null && source != null && owner != null) run {
            owner.lease.check()
            checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use {
                accounts.legacyAudio.export(owner, source, it)
            }
        }
    }
    LaunchedEffect(data.lease.generation) {
        try { preview = withContext(Dispatchers.IO) { accounts.legacyAudio.preview(data) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { failure = true }
    }
    if (failure) {
        Text(stringResource(R.string.legacy_audio_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { run {} }, enabled = !busy) { Text(stringResource(R.string.retry)) }
    }
    preview?.let { state ->
        if (state.expiredCount > 0) {
            Text(pluralStringResource(R.plurals.legacy_audio_expired, state.expiredCount, state.expiredCount),
                Modifier.testTag("legacy-expired"))
            TextButton(enabled = !busy, onClick = { run { accounts.legacyAudio.acknowledgeExpiry(data) } }) {
                Text(stringResource(R.string.legacy_audio_acknowledge))
            }
        }
        if (state.recordings.isNotEmpty()) {
            Text(stringResource(R.string.legacy_audio_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.legacy_audio_explanation))
            for (item in state.recordings) Column(Modifier.padding(top = 8.dp).testTag("legacy-recording")) {
                Text(dateFormat.format(Date(item.recording.createdAt)))
                Text(stringResource(R.string.voice_expires,
                    dateFormat.format(Date(item.recording.expiresAt))))
                if (!item.readable) Text(stringResource(R.string.legacy_audio_unreadable))
                androidx.compose.material3.OutlinedButton(enabled = !busy && item.readable, onClick = { run {
                    accounts.legacyAudio.recover(data, item.source)
                    data.lease.check()
                    withContext(Dispatchers.Main) { showVoice = true }
                } }, modifier = Modifier.testTag("legacy-recover")) { Text(stringResource(R.string.legacy_audio_recover)) }
                Text(stringResource(R.string.voice_export_warning), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedButton(enabled = !busy && item.readable, onClick = {
                    exportSource = item.source
                    exportOwner = data.lease.owner
                    exportGeneration = data.lease.generation
                    export.launch("bun-do-old-recording.wav")
                }, modifier = Modifier.testTag("legacy-export")) { Text(stringResource(R.string.voice_export)) }
                TextButton(enabled = !busy, onClick = { deleting = item.source }, modifier = Modifier.testTag("legacy-delete")) {
                    Text(stringResource(R.string.voice_delete))
                }
            }
        }
    }
    deleting?.let { key ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null },
            title = { Text(stringResource(R.string.voice_delete)) },
            text = { Text(stringResource(R.string.legacy_audio_delete_confirm)) },
            confirmButton = { TextButton(enabled = !busy, onClick = { run {
                accounts.legacyAudio.delete(data, key)
                withContext(Dispatchers.Main) { deleting = null }
            } }) { Text(stringResource(R.string.voice_delete)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text(stringResource(R.string.back)) } })
    }
    if (showVoice && reviewVoice) VoiceSheet(data.voice,
        onDismiss = { reviewVoice = false; data.voice.closeReview() },
        onType = { reviewVoice = false; data.voice.closeReview() },
        onSaved = {
            reviewVoice = false; showVoice = false
            scope.launch { data.selectHousehold(null); onSaved() }
        })
    if (showVoice && !reviewVoice) {
        val voiceState by data.voice.state.collectAsStateWithLifecycle()
        LaunchedEffect(voiceState.saved) {
            voiceState.saved?.let {
                data.voice.acknowledgeSaved()
                if (it.target == null) { showVoice = false; data.selectHousehold(null); onSaved() }
            }
        }
        ModalBottomSheet(onDismissRequest = { showVoice = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            VoiceSettings(data.voice, { reviewVoice = true }, initialHistory = true)
        }
    }
}
