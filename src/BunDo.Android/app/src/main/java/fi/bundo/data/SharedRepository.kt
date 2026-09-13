package fi.bundo.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

data class SharedRequest(val worker: String, val workspace: SharedWorkspace, val envelope: String?)

/** No transaction spans a network call. The Room worker token fences every response apply. */
class SharedRepository(
    private val database: InboxDatabase,
    private val lease: DataLease,
    val scope: String,
    private val registration: String,
) : TaskEditorRepository {
    private val dao = database.shared()
    val taskStates = combine(dao.projection(scope), dao.observeAllIntents(), dao.observeWorkspace(scope)) { rows, intents, state ->
        SharedTaskActions.ordered(rows, intents, state)
    }
    override val tasks = taskStates.map { rows -> rows.map(SharedProtocol::inbox) }
    override val drafts = dao.drafts(scope).map { rows -> rows.map { EditorDraft(it.key, it.title, it.description, it.savedAt) } }
    val workspace = dao.observeWorkspace(scope)
    val problems = dao.problems(scope)
    val recovery = dao.observeRecovery(scope)
    val canonical = dao.observeBase(scope).map { rows -> rows.associate { it.id to it.snapshot } }

    /** The displayed snapshot binds confirmation and dependencies to what the user actually saw. */
    suspend fun act(kind: String, displayed: String, confirmedClaimant: String? = null,
        after: String? = null, before: String? = null) = lease.access {
        require(kind in SharedTaskActions.kinds)
        database.withTransaction {
            val state = current()
            check(state.blocked == null && dao.recoveryState(scope) == null)
            val task = JSONObject(displayed)
            val id = task.getString("id")
            check(sameJson(task, JSONObject(checkNotNull(dao.task(scope, id)).snapshot))) { "Task changed" }
            val me = JSONObject(checkNotNull(state.membership)).getString("me")
            val sequence = state.nextSequence.toULong()
            check(sequence < ULong.MAX_VALUE)
            val prior = dao.taskIntents(scope, id).filter { SharedTaskActions.pending(it, state.revision) }
            val payload = JSONObject().put("taskId", id)
            if (kind == "CompleteTask") payload.put("confirmedClaimantId", confirmedClaimant ?: JSONObject.NULL)
            if (kind == "MoveTask") payload.put("expectedParentId", JSONObject.NULL)
                .put("afterTaskId", after ?: JSONObject.NULL).put("beforeTaskId", before ?: JSONObject.NULL)
            val action = SharedTaskActions.capture(kind, task, prior, payload, me)
            dao.saveIntent(SharedIntent(scope, sequence.toString(), id, kind, task.getString("title"), null,
                false, false, task.human("title"), task.human("description"), task.decimal("deletionVersion").toString(),
                prior.lastOrNull()?.sequence, SharedProtocol.context(), taskAction = action))
            val next = state.copy(nextSequence = (sequence + 1u).toString(), journalVersion = state.journalVersion + 1)
            dao.saveWorkspace(next)
            rebuild(next)
            lease.check()
        }
    }

    /** Only terminal variants can be dismissed. Pending identities must still obtain their outcome. */
    suspend fun dismiss(sequence: String) = lease.access {
        database.withTransaction {
            current()
            val intent = checkNotNull(dao.intents(scope).find { it.sequence == sequence })
            check(intent.status in listOf("REJECTED", "BLOCKED_DEPENDENCY", "QUARANTINED"))
            dao.saveIntent(intent.copy(status = "DISMISSED"))
        }
    }

    /** Confirmation is bound to the exact canonical state displayed, not a receipt's old conflict value. */
    suspend fun reapply(sequence: String, displayed: String) = lease.access {
        database.withTransaction {
            val state = current()
            check(state.blocked == null && dao.recoveryState(scope) == null)
            val intents = dao.intents(scope)
            val retained = checkNotNull(intents.find { it.sequence == sequence })
            check(retained.status in listOf("REJECTED", "BLOCKED_DEPENDENCY", "QUARANTINED"))
            check(retained.kind == "EditTask")
            check(intents.none { it.taskId == retained.taskId && it.status in listOf("PENDING", "SUBMITTED") })
            val task = JSONObject(checkNotNull(dao.baseTask(scope, state.baseGeneration, retained.taskId)).snapshot)
            check(sameJson(task, JSONObject(displayed)))
            val next = state.nextSequence.toULong()
            check(next < ULong.MAX_VALUE)
            val intent = SharedIntent(scope, next.toString(), retained.taskId, "EditTask",
                if (retained.titleChanged) retained.title else task.getString("title"),
                if (retained.descriptionChanged) retained.description else task.nullableString("description"),
                retained.titleChanged, retained.descriptionChanged, task.human("title"), task.human("description"),
                task.decimal("deletionVersion").toString(), null, SharedProtocol.context())
            dao.saveIntent(intent)
            dao.saveIntent(retained.copy(status = "DISMISSED"))
            dao.saveWorkspace(state.copy(nextSequence = (next + 1u).toString(), journalVersion = state.journalVersion + 1))
            rebuild(state)
            lease.check()
        }
    }

    private suspend fun current(): SharedWorkspace = checkNotNull(dao.workspace(scope)).also {
        lease.check()
        check(it.registration == registration)
    }

    override suspend fun draft(key: String): EditorDraft = lease.access {
        database.withTransaction {
            check(current().blocked == null)
            val saved = dao.draft(scope, key)
            if (saved != null) EditorDraft(key, saved.title, saved.description, saved.savedAt)
            else if (key == InboxRepository.NEW_DRAFT) EditorDraft(key)
            else {
                val basis = editBasis(key, current())
                val task = SharedProtocol.inbox(basis.getJSONObject("task"))
                dao.saveDraft(SharedDraft(scope, key, task.title, task.description, System.currentTimeMillis(), basis.toString()))
                EditorDraft(key, task.title, task.description)
            }
        }
    }

    override suspend fun saveDraft(draft: EditorDraft) = lease.access {
        database.withTransaction {
            check(current().blocked == null)
            val saved = dao.draft(scope, draft.key)
            val basis = if (saved != null) saved.basis else
                if (draft.key == InboxRepository.NEW_DRAFT) null else editBasis(draft.key, current()).toString()
            dao.saveDraft(SharedDraft(scope, draft.key, draft.title, draft.description, System.currentTimeMillis(), basis))
        }
    }

    /** Keep the displayed values and the exact local operation that supplied each group's precondition. */
    private suspend fun editBasis(id: String, state: SharedWorkspace): JSONObject {
        val task = JSONObject(checkNotNull(dao.task(scope, id)).snapshot)
        val pending = dao.intents(scope).filter { it.taskId == id &&
            it.status in listOf("PENDING", "SUBMITTED", "ACCEPTED") &&
            (it.receipt == null || JSONObject(it.receipt).decimal("effectRevision") > state.revision.toULong()) }
        return JSONObject().put("task", task)
            .put("titleAfterSequence", pending.lastOrNull { it.titleChanged }?.sequence ?: JSONObject.NULL)
            .put("descriptionAfterSequence", pending.lastOrNull { it.descriptionChanged }?.sequence ?: JSONObject.NULL)
            .put("deletionAfterSequence", pending.lastOrNull { it.kind == "CreateTask" }?.sequence ?: JSONObject.NULL)
    }

    override suspend fun commit(draft: EditorDraft): String = commit(draft, removeDraft = true)

    private suspend fun commit(draft: EditorDraft, removeDraft: Boolean): String = lease.access {
        require(InboxLimits.valid(draft.title, draft.description))
        database.withTransaction {
            val state = current()
            check(state.blocked == null)
            val sequence = state.nextSequence.toULong()
            check(sequence < ULong.MAX_VALUE)
            val creating = draft.key == InboxRepository.NEW_DRAFT
            val id = if (creating) SharedProtocol.taskId(registration, sequence.toString()) else draft.key
            val saved = if (creating) null else dao.draft(scope, id)
            // A pre-baseline draft remains exportable but cannot be silently rebased on a new shared value.
            val basis = if (creating) null else if (saved != null) JSONObject(checkNotNull(saved.basis))
                else editBasis(id, state)
            val old = basis?.getJSONObject("task")
            val titleChanged = creating || old!!.getString("title") != draft.title
            val descriptionChanged = creating || old!!.nullableString("description").orEmpty() != draft.description
            if (!titleChanged && !descriptionChanged) { dao.deleteDraft(scope, draft.key); return@withTransaction id }
            val prior = dao.intents(scope).lastOrNull { it.taskId == id && it.status in listOf("PENDING", "SUBMITTED", "ACCEPTED") &&
                (it.receipt == null || JSONObject(it.receipt).decimal("effectRevision") > state.revision.toULong()) }
            val intent = SharedIntent(scope, sequence.toString(), id, if (creating) "CreateTask" else "EditTask",
                draft.title, draft.description, titleChanged, descriptionChanged,
                old?.human("title") ?: "0", old?.human("description") ?: "0", old?.decimal("deletionVersion")?.toString() ?: "0",
                prior?.sequence, SharedProtocol.context(),
                titleAfterSequence = basis?.nullableString("titleAfterSequence"),
                descriptionAfterSequence = basis?.nullableString("descriptionAfterSequence"),
                deletionAfterSequence = basis?.nullableString("deletionAfterSequence"))
            dao.saveIntent(intent)
            dao.saveWorkspace(state.copy(nextSequence = (sequence + 1u).toString(), journalVersion = state.journalVersion + 1))
            if (removeDraft) dao.deleteDraft(scope, draft.key)
            if (dao.recoveryState(scope) == null) rebuild(state)
            else {
                val visible = if (creating) SharedProtocol.optimistic(intent)
                    else JSONObject(checkNotNull(dao.task(scope, id)).snapshot)
                        .put("title", draft.title).put("description", draft.description)
                dao.saveProjection(SharedProjection(scope, id, visible.toString(), state.projectionGeneration))
            }
            lease.check()
            id
        }
    }

    /** Imports copy selected text into new commands; never copy old envelopes, IDs or sequences. */
    suspend fun copyText(title: String, description: String): String =
        commit(EditorDraft(InboxRepository.NEW_DRAFT, title, description), removeDraft = false)

    suspend fun prepare(now: Long, boot: Int): SharedRequest? = lease.access {
        database.withTransaction {
            val state = current()
            if (state.blocked != null || state.worker != null && state.workerBoot == boot && state.workerUntil > now)
                return@withTransaction null
            val intents = dao.intents(scope)
            var next = intents.firstOrNull { it.status in listOf("PENDING", "SUBMITTED") }
            if (dao.recoveryState(scope) != null) next = null
            if (next != null && next.frozen == null) {
                val receipts = intents.filter { it.receipt != null }.associate { it.sequence to JSONObject(it.receipt!!) }
                if (SharedProtocol.dependencies(next).all { it in receipts }) {
                    next = next.copy(frozen = SharedProtocol.freeze(state, next, receipts), status = "SUBMITTED")
                    dao.saveIntent(next)
                } else next = null
            }
            val worker = UUID.randomUUID().toString()
            dao.saveWorkspace(state.copy(worker = worker, workerUntil = now + 90_000, workerBoot = boot))
            lease.check()
            SharedRequest(worker, state, next?.frozen)
        }
    }

    suspend fun release(request: SharedRequest) = lease.access {
        database.withTransaction {
            val state = current()
            if (state.worker == request.worker) dao.saveWorkspace(state.copy(worker = null, workerUntil = 0))
        }
    }

    suspend fun block(request: SharedRequest, reason: String) = lease.access {
        database.withTransaction {
            val state = current()
            if (state.worker != request.worker) return@withTransaction
            dao.saveWorkspace(state.copy(blocked = reason, worker = null))
            for (intent in dao.intents(scope)) {
                val quarantine = intent.status in listOf("PENDING", "SUBMITTED") ||
                    intent.status == "ACCEPTED" && intent.receipt?.let { JSONObject(it).decimal("effectRevision") > state.revision.toULong() } == true
                val receipt = if (reason == "FORBIDDEN") intent.receipt?.let {
                    JSONObject(it).put("task", JSONObject.NULL).toString()
                } else intent.receipt
                if (reason == "FORBIDDEN" && intent.status == "ACCEPTED" && !quarantine) {
                    dao.deleteIntent(scope, intent.sequence)
                    continue
                }
                dao.saveIntent(intent.copy(receipt = receipt,
                    title = if (reason == "FORBIDDEN" && !intent.titleChanged) "" else intent.title,
                    description = if (reason == "FORBIDDEN" && !intent.descriptionChanged) null else intent.description,
                    status = if (quarantine) "QUARANTINED" else intent.status,
                    problem = if (quarantine) reason else intent.problem))
            }
            if (reason == "FORBIDDEN") {
                dao.clearBase(scope)
                dao.deleteRecovery(scope)
                // Draft baselines can include remote values, while their edited text remains recoverable.
                for (draft in dao.allDrafts().filter { it.scope == scope }) {
                    val original = draft.basis?.let(::JSONObject)?.getJSONObject("task")
                    dao.saveDraft(draft.copy(basis = null,
                        title = if (original?.getString("title") == draft.title) "" else draft.title,
                        description = if (original?.nullableString("description").orEmpty() == draft.description) "" else draft.description))
                }
            }
            dao.clearProjection(scope)
            lease.check()
        }
    }

    /** Throws before commit for incomplete pages, invalid receipts and expired account/worker generations. */
    suspend fun apply(request: SharedRequest, response: JSONObject): Boolean = lease.access {
        database.withTransaction {
            val state = current()
            check(state.worker == request.worker && state.cursor == request.workspace.cursor && state.epoch == request.workspace.epoch)
            check(response.getString("workspaceId") == state.workspaceId && response.getString("stateEpoch") == state.epoch)
            val after = response.decimal("afterRevision")
            val through = response.decimal("throughRevision")
            val target = response.decimal("targetRevision")
            val head = response.decimal("headRevision")
            require(after == state.revision.toULong() && after <= through && through <= target && target <= head)
            require(response.getBoolean("hasMore") == (through < target))
            var expected = after
            val groups = response.getJSONArray("groups")
            for (index in 0 until groups.length()) {
                val group = groups.getJSONObject(index)
                check(expected < ULong.MAX_VALUE)
                require(group.decimal("revision") == expected + 1u)
                val parts = group.getJSONArray("parts")
                require(group.getInt("partCount") == 1 && parts.length() == 1)
                val part = parts.getJSONObject(0)
                require(part.getInt("partIndex") == 0)
                val payload = part.getString("payload")
                require(sha256(payload) == group.getString("digest"))
                val tasks = JSONArray(payload)
                val ids = part.getJSONArray("entityIds")
                require(tasks.length() == ids.length())
                val seen = mutableSetOf<String>()
                for (t in 0 until tasks.length()) {
                    val task = tasks.getJSONObject(t)
                    SharedProtocol.validateTask(task, expected + 1u)
                    val id = task.getString("id")
                    require(id == ids.getString(t) && seen.add(id))
                    dao.saveBase(SharedBase(scope, id, task.toString(), state.baseGeneration))
                }
                expected++
            }
            require(expected == through)
            val intents = dao.intents(scope).associateBy { it.sequence }
            val receipts = response.getJSONArray("receipts")
            for (index in 0 until receipts.length()) {
                val receipt = receipts.getJSONObject(index)
                val operationId = receipt.getString("operationId")
                require(operationId.startsWith("$registration:"))
                val intent = checkNotNull(intents[operationId.removePrefix("$registration:")])
                require(intent.frozen != null && sha256(intent.frozen) == receipt.getString("fingerprint"))
                require(receipt.decimal("effectRevision") in 1uL..head)
                if (intent.receipt != null) require(sameJson(JSONObject(intent.receipt), receipt))
                val code = receipt.getString("code")
                if (code == "ACCEPTED") {
                    val task = receipt.getJSONObject("task")
                    SharedProtocol.validateTask(task, receipt.decimal("effectRevision"))
                    require(task.getString("id") == intent.taskId)
                    if (intent.titleChanged) require(task.getString("title") == intent.title)
                    if (intent.descriptionChanged) require(task.nullableString("description") == intent.description)
                }
                dao.saveIntent(intent.copy(receipt = receipt.toString(), status = when (code) {
                    "ACCEPTED" -> "ACCEPTED"; "BLOCKED_DEPENDENCY" -> code; else -> "REJECTED"
                }, problem = code.takeUnless { it == "ACCEPTED" }))
            }
            val base = dao.base(scope).associate { it.id to JSONObject(it.snapshot) }
            val updated = dao.intents(scope)
            for (intent in updated.filter { it.status == "ACCEPTED" }) {
                val receipt = JSONObject(checkNotNull(intent.receipt))
                if (receipt.decimal("effectRevision") <= through && receipt.decimal("effectRevision") > state.snapshotRevision.toULong())
                    require(SharedProtocol.containsEffect(base[intent.taskId], receipt.getJSONObject("task")))
            }
            var acknowledged = state.acknowledged.toULong()
            for (intent in updated.filter { it.sequence.toULong() > acknowledged }) {
                if (intent.sequence.toULong() != acknowledged + 1u || intent.receipt == null ||
                    JSONObject(intent.receipt).decimal("effectRevision") > through) break
                acknowledged++
            }
            val next = state.copy(revision = through.toString(), cursor = response.getString("cursor"),
                acknowledged = acknowledged.toString(), worker = null, workerUntil = 0,
                membership = response.optJSONObject("membership")?.toString() ?: state.membership)
            dao.saveWorkspace(next)
            rebuild(next)
            lease.check()
            response.getBoolean("hasMore") || updated.any { it.status in listOf("PENDING", "SUBMITTED") } ||
                acknowledged > state.acknowledged.toULong()
        }
    }

    private suspend fun rebuild(state: SharedWorkspace) {
        val rebuilding = dao.recoveryState(scope) != null
        val projected = dao.generationBase(scope, if (rebuilding) state.projectionGeneration else state.baseGeneration)
            .associate { it.id to JSONObject(it.snapshot) }.toMutableMap()
        val intents = dao.intents(scope)
        val applied = mutableSetOf<String>()
        for (intent in intents) {
            if (intent.status in listOf("REJECTED", "BLOCKED_DEPENDENCY", "QUARANTINED", "DISMISSED")) continue
            if (intent.receipt != null && JSONObject(intent.receipt).decimal("effectRevision") <= state.revision.toULong()) continue
            var task = projected[intent.taskId]
            var problem: String? = null
            if (intent.kind == "CreateTask") {
                if (task == null) { task = SharedProtocol.optimistic(intent); projected[intent.taskId] = task }
            } else if (task == null) problem = "ENTITY_MISSING"
            else if (intent.taskAction != null) problem = SharedTaskActions.project(task, intent, intents, applied)
            else {
                val prerequisite = intent.afterSequence?.let { seq -> intents.find { it.sequence == seq }?.receipt?.let(::JSONObject) }
                fun version(sequence: String?, field: String, observed: String) = sequence?.let { seq ->
                    intents.find { it.sequence == seq }?.receipt?.let(::JSONObject)?.optJSONObject("task")?.human(field)
                } ?: observed
                val titleVersion = version(intent.titleAfterSequence, "title", intent.observedTitle)
                val descriptionVersion = version(intent.descriptionAfterSequence, "description", intent.observedDescription)
                if (prerequisite != null && prerequisite.getString("code") != "ACCEPTED") problem = "BLOCKED_DEPENDENCY"
                else if (intent.titleChanged && task.human("title").toULong() > titleVersion.toULong() ||
                    intent.descriptionChanged && task.human("description").toULong() > descriptionVersion.toULong()) problem = "FIELD_CONFLICT"
                else {
                    if (intent.titleChanged) task.put("title", intent.title)
                    if (intent.descriptionChanged) task.put("description", intent.description ?: JSONObject.NULL)
                }
            }
            if (problem == null) applied += intent.sequence
            if (intent.problem != problem) dao.saveIntent(intent.copy(problem = problem))
        }
        dao.clearProjectionGeneration(scope, state.projectionGeneration)
        if (state.blocked == null) for ((id, task) in projected)
            dao.saveProjection(SharedProjection(scope, id, task.toString(), state.projectionGeneration))
    }
}
