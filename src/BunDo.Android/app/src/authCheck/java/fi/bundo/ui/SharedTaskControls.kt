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
    val confirmedClaimant: String? = null, val after: String? = null, val before: String? = null, val until: String? = null,
    val expectedOrder: List<String>? = null)

internal fun taskActionLabel(kind: String) = when (kind) {
    "ConfigureRepeat" -> R.string.repeat_save
    "StopRepeat" -> R.string.repeat_stop
    "RequestCleanup" -> R.string.cleanup_request
    "RequestSplit" -> R.string.split_generate
    "CancelCleanup" -> R.string.cleanup_cancel
    "ApplyCleanup" -> R.string.cleanup_apply
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

internal fun taskClaimant(task: JSONObject, membership: JSONObject?): String? =
    task.nullableString("claimantId")?.takeIf { member(membership, it)?.optBoolean("active") == true }

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
    SharedDueSummary(task)
    val lifecycle = task.optString("lifecycle", "OPEN")
    val claimant = taskClaimant(task, membership)
    if (lifecycle != "OPEN") {
        val actor = memberName(membership, task.nullableString("lifecycleActorId"))
        Text(stringResource(if (lifecycle == "COMPLETED") R.string.task_completed_by else R.string.task_cancelled_by, actor),
            style = MaterialTheme.typography.bodyMedium)
        val instant = task.nullableString("lifecycleAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (instant != null) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
            LocalConfiguration.current.locales[0]).format(Date.from(instant)), style = MaterialTheme.typography.bodySmall)
    }
    ClaimantIllustration(task.getString("id"), claimant?.takeIf { lifecycle == "OPEN" },
        if (claimant == null) "" else stringResource(R.string.task_claimed_by, memberName(membership, claimant)))
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SharedTaskControls(task: JSONObject, ordered: List<JSONObject>, membership: JSONObject?,
    enabled: Boolean, failed: Boolean, onAction: (SharedTaskAction) -> Unit,
    section: TaskDetailSection = TaskDetailSection.MORE) {
    val id = task.getString("id")
    val open = task.optString("lifecycle", "OPEN") == "OPEN"
    val me = membership?.optString("me")
    val claimant = taskClaimant(task, membership)
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
        if (section == TaskDetailSection.CONTENT) SharedTaskSummary(task, membership)
        if (section == TaskDetailSection.CONTENT && failed) Text(stringResource(R.string.task_changed_retry), color = MaterialTheme.colorScheme.error)
        if (!task.isNull("deletion") && section == TaskDetailSection.PRIMARY) {
            OutlinedButton(enabled = canAct && !task.getJSONObject("deletion").optBoolean("purging") &&
                (parentId == null || tasks[parentId]?.isNull("deletion") == true),
                modifier = Modifier.testTag("task-restore"),
                onClick = { onAction(SharedTaskAction("RestoreTask", task.toString())) }) { Text(stringResource(R.string.task_restore)) }
            if (task.getJSONObject("deletion").optBoolean("purging")) Text(stringResource(R.string.task_purging))
        } else if (task.isNull("deletion")) {
            if (section == TaskDetailSection.CONTENT && snoozed) Text(stringResource(R.string.task_snoozed))
            if (section == TaskDetailSection.CONTENT && isChecklist && task.optBoolean("emptyChecklist")) Text(stringResource(if (task.optBoolean("emptyChecklist")) R.string.checklist_empty else R.string.checklist_derived))
            if (open && !isChecklist) {
                if (section == TaskDetailSection.PRIMARY) Button(enabled = canAct && !snoozed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task-complete"), onClick = {
                    val action = SharedTaskAction("CompleteTask", task.toString(), claimant?.takeIf { it != me })
                    if (claimant != null && claimant != me) confirmation = action else onAction(action)
                }) { Text(stringResource(R.string.task_complete)) }
                if (section == TaskDetailSection.MORE) {
                if (claimant == null) OutlinedButton(enabled = canAct && !snoozed, modifier = Modifier.testTag("task-claim"),
                    onClick = { onAction(SharedTaskAction("ClaimTask", task.toString())) }) { Text(stringResource(R.string.task_claim)) }
                else if (claimant == me || membership?.optString("ownerId") == me) OutlinedButton(enabled = canAct,
                    modifier = Modifier.testTag("task-unclaim"),
                    onClick = { onAction(SharedTaskAction("UnclaimTask", task.toString())) }) { Text(stringResource(R.string.task_unclaim)) }
            }
            }
            if (section == TaskDetailSection.PRIMARY && !open && (!isChecklist || !task.isNull("cancellationGroupId"))) OutlinedButton(enabled = canAct,
                modifier = Modifier.testTag("task-reopen"),
                onClick = { onAction(SharedTaskAction("ReopenTask", task.toString())) }) { Text(stringResource(R.string.task_reopen)) }
            if (section == TaskDetailSection.STEPS) SharedCleanupControls(task, canAct, onAction)
            if (open) {
                if (section == TaskDetailSection.SCHEDULE) SharedSnoozePresets(task, membership, canAct, onAction)
                if (section == TaskDetailSection.MORE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = canAct && index > 0, modifier = Modifier.testTag("task-earlier"), onClick = {
                        onAction(SharedTaskAction("MoveTask", task.toString(), after = active.getOrNull(index - 2), before = active[index - 1]))
                    }) { Text(stringResource(R.string.task_earlier)) }
                    OutlinedButton(enabled = canAct && index >= 0 && index < active.lastIndex, modifier = Modifier.testTag("task-later"), onClick = {
                        onAction(SharedTaskAction("MoveTask", task.toString(), after = active[index + 1], before = active.getOrNull(index + 2)))
                    }) { Text(stringResource(R.string.task_later)) }
                }
                OutlinedButton(enabled = canAct, modifier = Modifier.testTag("task-cancel"),
                    onClick = { onAction(SharedTaskAction("CancelTask", task.toString())) }) { Text(stringResource(R.string.task_cancel)) }
                }
                if (section == TaskDetailSection.SCHEDULE && task.isNull("snoozedUntil")) OutlinedButton(enabled = canAct, modifier = Modifier.testTag("task-snooze"),
                    onClick = { onAction(SharedTaskAction("SetSnooze", task.toString(), until = Instant.now().plusSeconds(3600).toString())) }) {
                    Text(stringResource(R.string.task_snooze_hour))
                }
            }
            if (section == TaskDetailSection.SCHEDULE && !task.isNull("snoozedUntil")) OutlinedButton(enabled = canAct, modifier = Modifier.testTag("task-unsnooze"),
                onClick = { onAction(SharedTaskAction("ClearSnooze", task.toString())) }) { Text(stringResource(R.string.task_unsnooze)) }
            if (section == TaskDetailSection.MORE) OutlinedButton(enabled = canAct, modifier = Modifier.testTag("task-delete"),
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
