package fi.bundo.data

import androidx.room.withTransaction
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal interface SnapshotTransport {
    suspend fun manifest(state: SharedWorkspace, id: String): JSONObject
    suspend fun chunk(state: SharedWorkspace, id: String, index: Int): ByteArray
    suspend fun outcomes(state: SharedWorkspace, first: String, count: Int): JSONObject
}

/** Network and disk steps are restartable; each transaction checks both account and worker ownership. */
internal class SharedSnapshotRecovery(
    private val database: InboxDatabase,
    private val lease: DataLease,
    private val scope: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.shared()

    private suspend fun state(request: SharedRequest): SharedWorkspace = checkNotNull(dao.workspace(scope)).also {
        lease.check()
        check(it.worker == request.worker && it.registration == request.workspace.registration &&
            it.epoch == request.workspace.epoch && it.blocked == null)
    }

    suspend fun pending(): Boolean = lease.access { dao.recoveryState(scope) != null }

    suspend fun begin(request: SharedRequest) = lease.access {
        database.withTransaction {
            state(request)
            if (dao.recoveryState(scope) == null) dao.saveRecovery(SharedRecovery(scope, UUID.randomUUID().toString()))
        }
    }

    private suspend fun <T> transaction(request: SharedRequest, action: suspend () -> T): T = lease.access {
        database.withTransaction {
            state(request)
            val result = action()
            lease.check()
            result
        }
    }

    /** One bounded step. A caller may stop after any step, including process death. */
    suspend fun step(request: SharedRequest, transport: SnapshotTransport, freeBytes: Long, existingBytes: Long): Boolean {
        val current = transaction(request) { state(request) }
        val recovery = transaction(request) { checkNotNull(dao.recoveryState(scope)) }
        try {
            if (recovery.manifest == null) {
                val reclaimed = transaction(request) {
                    dao.reclaimBase(scope, current.baseGeneration, recovery.snapshotId) +
                        dao.reclaimProjection(scope, current.projectionGeneration, recovery.snapshotId)
                }
                if (reclaimed > 0) return true
                val manifest = transport.manifest(current, recovery.snapshotId)
                validateManifest(current, recovery, manifest)
                // JSON rows and their projection both occupy SQLite pages. Keep a conservative expansion allowance.
                val required = Math.addExact(Math.multiplyExact(manifest.getLong("totalBytes"), 4L),
                    Math.addExact(existingBytes / 4, 64L * 1024 * 1024))
                if (freeBytes < required) throw SyncFailure("STORAGE_REQUIRED")
                transaction(request) {
                    state(request)
                    dao.saveRecovery(recovery.copy(manifest = manifest.toString(), problem = null))
                }
                return true
            }
            val manifest = JSONObject(recovery.manifest)
            if (recovery.phase == "DOWNLOAD" && Instant.parse(manifest.getString("expiresAt")).toEpochMilli() <= now())
                throw SyncFailure("SNAPSHOT_EXPIRED")
            when (recovery.phase) {
                "DOWNLOAD" -> {
                    val chunks = manifest.getJSONArray("chunks")
                    if (recovery.nextChunk < chunks.length()) {
                        val descriptor = chunks.getJSONObject(recovery.nextChunk)
                        val bytes = transport.chunk(current, recovery.snapshotId, recovery.nextChunk)
                        require(bytes.size == descriptor.getInt("bytes") && digest(bytes) == descriptor.getString("digest"))
                        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        val chunk = JSONObject(decoder.decode(ByteBuffer.wrap(bytes)).toString())
                        if (chunk.getInt("schemaVersion") != 1) throw SyncFailure("UNSUPPORTED_SNAPSHOT")
                        val tasks = chunk.getJSONArray("tasks")
                        require(tasks.length() == descriptor.getInt("documents"))
                        transaction(request) {
                            state(request)
                            for (index in 0 until tasks.length()) {
                                val task = tasks.getJSONObject(index)
                                SharedProtocol.validateTask(task, manifest.decimal("revision"))
                                val id = task.getString("id")
                                require(dao.baseTask(scope, recovery.snapshotId, id) == null)
                                dao.saveBase(SharedBase(scope, id, task.toString(), recovery.snapshotId))
                            }
                            dao.saveRecovery(recovery.copy(nextChunk = recovery.nextChunk + 1, problem = null))
                        }
                    } else transaction(request) {
                        state(request)
                        require(dao.baseCount(scope, recovery.snapshotId) == manifest.getInt("documentCount"))
                        manifest.optJSONArray("rootOrder")?.let {
                            val order = SharedTaskActions.orderEntity(it, manifest.getString("revision"))
                            dao.saveBase(SharedBase(scope, SharedTaskActions.ORDER_ID, order.toString(), recovery.snapshotId))
                        }
                        dao.saveRecovery(recovery.copy(phase = "OUTCOMES", afterId = "0"))
                    }
                }
                "OUTCOMES" -> {
                    val pending = transaction(request) { dao.intents(scope) }.firstOrNull { it.sequence.toULong() > recovery.afterId.toULong() &&
                        it.frozen != null && (it.status in listOf("PENDING", "SUBMITTED") ||
                            it.status in listOf("QUARANTINED", "DISMISSED") && it.problem == "RECOVERY_DEPENDENCY") }
                    if (pending != null) {
                        // One receipt can contain bounded task text. Avoid holding a large receipt range in memory.
                        val reply = transport.outcomes(current, pending.sequence, 1)
                        transaction(request) {
                            state(request)
                            // Dismissal may happen while the network request is in flight.
                            val latest = checkNotNull(dao.intents(scope).find { it.sequence == pending.sequence })
                            reconcile(current, latest, reply)
                            dao.saveRecovery(recovery.copy(afterId = pending.sequence))
                        }
                    } else transaction(request) {
                        val latest = state(request)
                        dao.saveWorkspace(latest.copy(baseGeneration = recovery.snapshotId))
                        dao.saveRecovery(recovery.copy(phase = "BASE", afterId = ""))
                    }
                }
                "BASE" -> transaction(request) {
                    state(request)
                    val page = dao.basePage(scope, recovery.snapshotId, recovery.afterId)
                    for (row in page) dao.saveProjection(SharedProjection(scope, row.id, row.snapshot, recovery.snapshotId))
                    dao.saveRecovery(if (page.isEmpty()) recovery.copy(phase = "REPLAY", afterId = "", replayVersion = current.journalVersion)
                        else recovery.copy(afterId = page.last().id))
                }
                "REPLAY" -> transaction(request) {
                    val latest = state(request)
                    val page = dao.intentTaskPage(scope, recovery.afterId)
                    for (id in page) replay(id, recovery.snapshotId, manifest.decimal("revision"))
                    if (page.isNotEmpty()) dao.saveRecovery(recovery.copy(afterId = page.last()))
                    else if (latest.journalVersion != recovery.replayVersion)
                        dao.saveRecovery(recovery.copy(afterId = "", replayVersion = latest.journalVersion))
                    else {
                        dao.saveWorkspace(latest.copy(projectionGeneration = recovery.snapshotId,
                            revision = manifest.getString("revision"), snapshotRevision = manifest.getString("revision"),
                            cursor = manifest.getString("cursor")))
                        dao.saveRecovery(recovery.copy(phase = "CLEANUP"))
                    }
                }
                "CLEANUP" -> transaction(request) {
                    val latest = state(request)
                    val deleted = dao.reclaimBase(scope, latest.baseGeneration, latest.baseGeneration) +
                        dao.reclaimProjection(scope, latest.projectionGeneration, latest.projectionGeneration)
                    if (deleted == 0) {
                        dao.deleteRecovery(scope)
                        if (dao.intents(scope).any { it.problem == "RECOVERY_DEPENDENCY" })
                            dao.saveWorkspace(latest.copy(blocked = "REGISTRATION_REPLACEMENT_REQUIRED"))
                    }
                }
                else -> throw SyncFailure("UNSUPPORTED_SNAPSHOT")
            }
            return lease.access { dao.recoveryState(scope) != null }
        } catch (error: SyncFailure) {
            if (error.code in listOf("SNAPSHOT_EXPIRED", "SNAPSHOT_CORRUPT")) {
                transaction(request) {
                    val latest = state(request)
                    // A verified installed generation is no longer dependent on server artifact availability.
                    check(latest.baseGeneration != recovery.snapshotId)
                    dao.saveRecovery(SharedRecovery(scope, UUID.randomUUID().toString(), problem = error.code))
                }
            } else transaction(request) {
                state(request)
                dao.saveRecovery(recovery.copy(problem = error.code))
            }
            throw error
        } catch (error: Exception) {
            if (error !is IllegalArgumentException && error !is org.json.JSONException &&
                error !is java.nio.charset.CharacterCodingException) throw error
            transaction(request) { dao.saveRecovery(recovery.copy(problem = "SNAPSHOT_CORRUPT")) }
            throw SyncFailure("SNAPSHOT_CORRUPT")
        }
    }

    private fun validateManifest(state: SharedWorkspace, recovery: SharedRecovery, manifest: JSONObject) {
        if (manifest.getInt("schemaVersion") != 1) throw SyncFailure("UNSUPPORTED_SNAPSHOT")
        require(manifest.getString("snapshotId") == recovery.snapshotId && manifest.getString("workspaceId") == state.workspaceId &&
            manifest.getString("stateEpoch") == state.epoch)
        require(manifest.decimal("revision") >= state.revision.toULong())
        if (Instant.parse(manifest.getString("expiresAt")).toEpochMilli() <= now()) throw SyncFailure("SNAPSHOT_EXPIRED")
        require(manifest.getString("cursor").isNotEmpty())
        val chunks = manifest.getJSONArray("chunks")
        require(chunks.length() <= 4096)
        var bytes = 0L
        var documents = 0L
        for (index in 0 until chunks.length()) {
            val chunk = chunks.getJSONObject(index)
            require(chunk.getInt("index") == index && chunk.getInt("bytes") in 1..4 * 1024 * 1024 && chunk.getInt("documents") > 0)
            require(chunk.getString("digest").matches(Regex("[0-9a-f]{64}")))
            bytes += chunk.getInt("bytes"); documents += chunk.getInt("documents")
        }
        require(bytes <= 1024L * 1024 * 1024 && bytes == manifest.getLong("totalBytes") && documents == manifest.getLong("documentCount"))
        manifest.optJSONArray("rootOrder")?.let {
            SharedProtocol.validateTask(SharedTaskActions.orderEntity(it, manifest.getString("revision")), manifest.decimal("revision"))
        }
    }

    private suspend fun reconcile(state: SharedWorkspace, intent: SharedIntent, reply: JSONObject) {
        if (reply.getString("code") != "ACCEPTED") throw SyncFailure(reply.getString("code"))
        require(reply.getString("workspaceId") == state.workspaceId && reply.getString("stateEpoch") == state.epoch &&
            reply.getString("deviceId") == state.registration)
        val outcomes = reply.getJSONArray("outcomes")
        require(outcomes.length() == 1)
        val outcome = outcomes.getJSONObject(0)
        require(outcome.decimal("sequence") == intent.sequence.toULong())
        val highWater = reply.decimal("highWater")
        when (outcome.getString("state")) {
            "NOT_SEEN" -> require(intent.sequence.toULong() > highWater)
            "OUTCOME_EXPIRED" -> {
                require(intent.sequence.toULong() <= highWater)
                dao.saveIntent(intent.copy(status = if (intent.status == "DISMISSED") "DISMISSED" else "QUARANTINED",
                    problem = "OUTCOME_EXPIRED"))
                // Dependencies without retained output versions cannot be safely frozen. Keep the entire suffix
                // explicit rather than creating a sequence gap by silently skipping an allocated operation.
                for (later in dao.intents(scope).filter { it.sequence.toULong() > intent.sequence.toULong() && it.status in listOf("PENDING", "SUBMITTED") })
                    dao.saveIntent(later.copy(status = "QUARANTINED", problem = "RECOVERY_DEPENDENCY"))
            }
            "ACCEPTED", "REJECTED" -> {
                require(intent.sequence.toULong() <= highWater)
                val receipt = outcome.getJSONObject("receipt")
                require(receipt.getString("operationId") == "${state.registration}:${intent.sequence}" &&
                    receipt.getString("fingerprint") == sha256(checkNotNull(intent.frozen)) &&
                    outcome.getString("fingerprint") == receipt.getString("fingerprint") &&
                    outcome.decimal("effectRevision") == receipt.decimal("effectRevision"))
                val code = receipt.getString("code")
                require((code == "ACCEPTED") == (outcome.getString("state") == "ACCEPTED"))
                if (code == "ACCEPTED") {
                    val task = receipt.getJSONObject("task")
                    SharedProtocol.validateTask(task, receipt.decimal("effectRevision"))
                    require(task.getString("id") == intent.taskId)
                    if (intent.titleChanged) require(task.getString("title") == intent.title)
                    if (intent.descriptionChanged) require(task.nullableString("description") == intent.description)
                }
                dao.saveIntent(intent.copy(receipt = receipt.toString(), status = when {
                    intent.status == "DISMISSED" -> "DISMISSED"
                    code == "ACCEPTED" -> "ACCEPTED"
                    else -> "REJECTED"
                },
                    problem = code.takeUnless { it == "ACCEPTED" }))
            }
            else -> throw SyncFailure("UNSUPPORTED_SNAPSHOT")
        }
    }

    private suspend fun replay(id: String, generation: String, revision: ULong) {
        var task = dao.baseTask(scope, generation, id)?.snapshot?.let(::JSONObject)
        val intents = dao.taskIntents(scope, id)
        val applied = mutableSetOf<String>()
        for (intent in intents) {
            if (intent.status in listOf("REJECTED", "BLOCKED_DEPENDENCY", "QUARANTINED", "DISMISSED")) continue
            if (intent.receipt?.let { JSONObject(it).decimal("effectRevision") <= revision } == true) continue
            var problem: String? = null
            if (intent.kind == "CreateTask") {
                if (task == null) task = SharedProtocol.optimistic(intent)
            } else if (task == null) problem = "ENTITY_MISSING"
            else if (intent.taskAction != null) problem = SharedTaskActions.project(task, intent, intents, applied)
            else {
                fun version(sequence: String?, field: String, observed: String) = sequence?.let { seq ->
                    intents.find { it.sequence == seq }?.receipt?.let(::JSONObject)?.optJSONObject("task")?.human(field)
                } ?: observed
                val dependency = intent.afterSequence?.let { seq -> intents.find { it.sequence == seq } }
                problem = when {
                    dependency?.status in listOf("QUARANTINED", "REJECTED", "BLOCKED_DEPENDENCY") -> "BLOCKED_DEPENDENCY"
                    !task.isNull("deletion") -> "TASK_DELETED"
                    task.decimal("deletionVersion").toString() != (intent.deletionAfterSequence?.let { seq ->
                        intents.find { it.sequence == seq }?.receipt?.let(::JSONObject)?.optJSONObject("task")?.decimal("deletionVersion")?.toString()
                    } ?: intent.observedDeletion) -> "STALE_LIFECYCLE"
                    intent.titleChanged && task.human("title").toULong() > version(intent.titleAfterSequence, "title", intent.observedTitle).toULong() ||
                        intent.descriptionChanged && task.human("description").toULong() > version(intent.descriptionAfterSequence, "description", intent.observedDescription).toULong() -> "FIELD_CONFLICT"
                    else -> null
                }
                if (problem == null) {
                    if (intent.titleChanged) task.put("title", intent.title)
                    if (intent.descriptionChanged) task.put("description", intent.description ?: JSONObject.NULL)
                }
            }
            if (problem == null) applied += intent.sequence
            dao.saveIntent(intent.copy(problem = problem))
        }
        dao.deleteProjectionTask(scope, generation, id)
        if (task != null) dao.saveProjection(SharedProjection(scope, id, task.toString(), generation))
    }

    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
