package fi.bundo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.nullableString
import org.json.JSONObject

@Composable
internal fun SharedCleanupControls(task: JSONObject, enabled: Boolean, onAction: (SharedTaskAction) -> Unit) {
    val cleanup = task.optJSONObject("cleanup")
    val status = cleanup?.optString("status")
    val splitting = cleanup?.optString("mode") == "SPLIT"
    if (splitting && cleanup?.optString("status") !in listOf("APPLIED", "SUPERSEDED")) return
    val pending = status in listOf("PENDING", "RUNNING")
    var compare by remember(task.getString("id"), cleanup?.optString("id")) { mutableStateOf(false) }
    val open = task.optString("lifecycle", "OPEN") == "OPEN"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pending || status == "FAILED" || status == "READY") Text(stringResource(when {
            pending -> R.string.cleanup_pending
            status == "READY" -> R.string.cleanup_ready
            else -> R.string.cleanup_failed
        }), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (open && !pending) OutlinedButton(enabled = enabled, modifier = Modifier.testTag("cleanup-request"),
            onClick = { onAction(SharedTaskAction("RequestCleanup", task.toString())) }) {
            Text(stringResource(if (status == "FAILED" || status == "SUPERSEDED") R.string.cleanup_retry else R.string.cleanup_request))
        }
        if (pending) TextButton(enabled = enabled, modifier = Modifier.testTag("cleanup-cancel"),
            onClick = { onAction(SharedTaskAction("CancelCleanup", task.toString())) }) { Text(stringResource(R.string.cleanup_cancel)) }
        if (status == "READY") FilledTonalButton(modifier = Modifier.testTag("cleanup-compare"), onClick = { compare = !compare }) {
            Text(stringResource(R.string.cleanup_compare))
        }
        if (compare && status == "READY") cleanup?.optJSONObject("proposal")?.let { proposal ->
            Text(stringResource(R.string.cleanup_current), style = MaterialTheme.typography.titleSmall)
            Text(task.getString("title"))
            task.nullableString("description")?.let { Text(it) }
            task.optJSONObject("due")?.let { Text(dueLabel(it)) }
            Text(stringResource(R.string.cleanup_suggestion), style = MaterialTheme.typography.titleSmall)
            Text(proposal.getString("title"))
            proposal.nullableString("description")?.let { Text(it) }
            proposal.optJSONObject("due")?.let { Text(stringResource(R.string.detail_due) + ": " + dueLabel(it)) }
            OutlinedButton(enabled = enabled && open, modifier = Modifier.testTag("cleanup-apply"),
                onClick = { onAction(SharedTaskAction("ApplyCleanup", task.toString())) }) { Text(stringResource(R.string.cleanup_apply)) }
        }
    }
}
