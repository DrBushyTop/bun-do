package fi.bundo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.nullableString
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.util.Date

internal data class SharedTaskAction(val kind: String, val displayed: String,
    val confirmedClaimant: String? = null, val after: String? = null, val before: String? = null, val until: String? = null)

internal fun taskActionLabel(kind: String) = when (kind) {
    "ClaimTask" -> R.string.task_claim
    "UnclaimTask" -> R.string.task_unclaim
    "CompleteTask" -> R.string.task_complete
    "ReopenTask" -> R.string.task_reopen
    "CancelTask" -> R.string.task_cancel
    "DeleteTask" -> R.string.task_delete
    "RestoreTask" -> R.string.task_restore
    "SplitTask", "AddChildren" -> R.string.checklist_add
    "SetSnooze", "ClearSnooze" -> R.string.task_snooze
    else -> R.string.task_move
}

private fun member(membership: JSONObject?, id: String?): JSONObject? {
    val members = membership?.optJSONArray("members") ?: return null
    return (0 until members.length()).map(members::getJSONObject).find { it.getString("id") == id }
}

@Composable
internal fun memberName(membership: JSONObject?, id: String?): String {
    val person = member(membership, id)
    return if (person == null || !person.optBoolean("active")) stringResource(R.string.task_former_member)
        else person.optString("displayName").ifBlank { stringResource(R.string.task_household_member) }
}

@Composable
internal fun SharedTaskSummary(task: JSONObject, membership: JSONObject?) {
    if (!task.isNull("deletion")) {
        Text(stringResource(R.string.task_deleted), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val lifecycle = task.optString("lifecycle", "OPEN")
    val claimant = task.nullableString("claimantId")?.takeIf { member(membership, it)?.optBoolean("active") == true }
    if (lifecycle != "OPEN") {
        val actor = memberName(membership, task.nullableString("lifecycleActorId"))
        Text(stringResource(if (lifecycle == "COMPLETED") R.string.task_completed_by else R.string.task_cancelled_by, actor),
            style = MaterialTheme.typography.bodyMedium)
        val instant = task.nullableString("lifecycleAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (instant != null) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
            LocalConfiguration.current.locales[0]).format(Date.from(instant)), style = MaterialTheme.typography.bodySmall)
        else Text(stringResource(R.string.task_waiting_sync), style = MaterialTheme.typography.bodySmall)
    } else if (claimant != null) Text(
        if (claimant == membership?.optString("me")) stringResource(R.string.task_claimed_you)
        else stringResource(R.string.task_claimed_by, memberName(membership, claimant)),
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SharedTaskControls(task: JSONObject, ordered: List<JSONObject>, membership: JSONObject?,
    enabled: Boolean, failed: Boolean, onAction: (SharedTaskAction) -> Unit) {
    val id = task.getString("id")
    val open = task.optString("lifecycle", "OPEN") == "OPEN"
    val me = membership?.optString("me")
    val claimant = task.nullableString("claimantId")?.takeIf { member(membership, it)?.optBoolean("active") == true }
    var confirmation by remember(id) { mutableStateOf<SharedTaskAction?>(null) }
    val tasks = ordered.associateBy { it.getString("id") }
    val parentId = task.nullableString("parentId")
    val isChecklist = task.optBoolean("isChecklist")
    val snoozed = fi.bundo.data.SharedChecklistActions.snoozed(task, tasks)
    val siblings = if (parentId != null) tasks[parentId]?.let(fi.bundo.data.SharedChecklistActions::childIds).orEmpty()
        .mapNotNull(tasks::get) else ordered
    val active = siblings.filter { it.nullableString("parentId") == parentId && it.isNull("deletion") &&
        (parentId != null || it.optString("lifecycle", "OPEN") == "OPEN") }.map { it.getString("id") }
    val index = active.indexOf(id)
    val canAct = enabled && me != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SharedTaskSummary(task, membership)
        if (failed) Text(stringResource(R.string.task_changed_retry), color = MaterialTheme.colorScheme.error)
        if (!task.isNull("deletion")) {
            Text(stringResource(R.string.task_restore_explanation))
            OutlinedButton(enabled = canAct && !task.getJSONObject("deletion").optBoolean("purging") &&
                (parentId == null || tasks[parentId]?.isNull("deletion") == true),
                modifier = Modifier.testTag("task-restore"),
                onClick = { onAction(SharedTaskAction("RestoreTask", task.toString())) }) { Text(stringResource(R.string.task_restore)) }
            if (task.getJSONObject("deletion").optBoolean("purging")) Text(stringResource(R.string.task_purging))
        } else {
            if (snoozed) Text(stringResource(R.string.task_snoozed))
            if (isChecklist) Text(stringResource(if (task.optBoolean("emptyChecklist")) R.string.checklist_empty else R.string.checklist_derived))
            if (open && !isChecklist) {
                Button(enabled = canAct && !snoozed, modifier = Modifier.testTag("task-complete"), onClick = {
                    val action = SharedTaskAction("CompleteTask", task.toString(), claimant?.takeIf { it != me })
                    if (claimant != null && claimant != me) confirmation = action else onAction(action)
                }) { Text(stringResource(R.string.task_complete)) }
                if (claimant == null) OutlinedButton(enabled = canAct && !snoozed, modifier = Modifier.testTag("task-claim"),
                    onClick = { onAction(SharedTaskAction("ClaimTask", task.toString())) }) { Text(stringResource(R.string.task_claim)) }
                else if (claimant == me || membership?.optString("ownerId") == me) OutlinedButton(enabled = canAct,
                    modifier = Modifier.testTag("task-unclaim"),
                    onClick = { onAction(SharedTaskAction("UnclaimTask", task.toString())) }) { Text(stringResource(R.string.task_unclaim)) }
            }
            if (!open && (!isChecklist || !task.isNull("cancellationGroupId"))) OutlinedButton(enabled = canAct,
                modifier = Modifier.testTag("task-reopen"),
                onClick = { onAction(SharedTaskAction("ReopenTask", task.toString())) }) { Text(stringResource(R.string.task_reopen)) }
            if (open) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = canAct && index > 0, modifier = Modifier.testTag("task-earlier"), onClick = {
                        onAction(SharedTaskAction("MoveTask", task.toString(), after = active.getOrNull(index - 2), before = active[index - 1]))
                    }) { Text(stringResource(R.string.task_earlier)) }
                    TextButton(enabled = canAct && index >= 0 && index < active.lastIndex, modifier = Modifier.testTag("task-later"), onClick = {
                        onAction(SharedTaskAction("MoveTask", task.toString(), after = active[index + 1], before = active.getOrNull(index + 2)))
                    }) { Text(stringResource(R.string.task_later)) }
                }
                TextButton(enabled = canAct, modifier = Modifier.testTag("task-cancel"),
                    onClick = { onAction(SharedTaskAction("CancelTask", task.toString())) }) { Text(stringResource(R.string.task_cancel)) }
                if (task.isNull("snoozedUntil")) TextButton(enabled = canAct, modifier = Modifier.testTag("task-snooze"),
                    onClick = { onAction(SharedTaskAction("SetSnooze", task.toString(), until = Instant.now().plusSeconds(3600).toString())) }) {
                    Text(stringResource(R.string.task_snooze_hour))
                }
            }
            if (!task.isNull("snoozedUntil")) TextButton(enabled = canAct, modifier = Modifier.testTag("task-unsnooze"),
                onClick = { onAction(SharedTaskAction("ClearSnooze", task.toString())) }) { Text(stringResource(R.string.task_unsnooze)) }
            TextButton(enabled = canAct, modifier = Modifier.testTag("task-delete"),
                onClick = { onAction(SharedTaskAction("DeleteTask", task.toString())) }) { Text(stringResource(R.string.task_delete)) }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.task_complete)) },
            text = { Text(stringResource(R.string.task_confirm_other, memberName(membership, action.confirmedClaimant))) },
            confirmButton = { TextButton(enabled = canAct, modifier = Modifier.testTag("task-confirm-complete"), onClick = {
                confirmation = null
                onAction(action)
            }) { Text(stringResource(R.string.task_complete)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.back)) } })
    }
}
