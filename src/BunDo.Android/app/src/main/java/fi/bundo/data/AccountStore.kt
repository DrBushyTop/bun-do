package fi.bundo.data

import android.app.NotificationManager
import android.content.Context
import androidx.room.withTransaction
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Constraints
import androidx.work.NetworkType
import fi.bundo.identity.ValidatedIdentity
import fi.bundo.identity.VerifiedSession
import fi.bundo.speech.VoiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class AccountData internal constructor(
    val identity: ValidatedIdentity?,
    val lease: DataLease,
    internal val database: InboxDatabase,
    val recordings: RecordingStore,
    internal val directory: File?,
    val registrationId: String?,
    private val context: Context,
) {
    val inbox = InboxRepository(database, lease)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal fun startReminders() {
        if (identity == null) return
        fi.bundo.reminders.ReminderWorker.start(context, this)
        scope.launch {
            database.invalidationTracker.createFlow("shared_workspaces", "shared_projection", "reminder_settings").collect {
                try {
                    ReminderCoordinator(this@AccountData, fi.bundo.reminders.AndroidReminders(context, this@AccountData)).reconcile()
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { fi.bundo.reminders.ReminderWorker.request(context, this@AccountData) }
            }
        }
    }
    private val selectedHouseholdValue = MutableStateFlow<String?>(null)
    val selectedHousehold = selectedHouseholdValue.asStateFlow()
    private val selectedWorkspaceValue = MutableStateFlow<SharedWorkspace?>(null)
    val selectedWorkspace = selectedWorkspaceValue.asStateFlow()
    init {
        scope.launch {
            try {
                database.shared().selected(registrationId.orEmpty()).collect { value ->
                    lease.check()
                    selectedWorkspaceValue.value = value
                    selectedHouseholdValue.value = value?.workspaceId
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* The editor owns the recoverable database-read error state. */ }
        }
    }
    suspend fun selectHousehold(id: String?, epoch: String? = null, name: String = "") = lease.access {
        database.withTransaction {
            database.shared().clearSelection()
            if (id != null) {
                val registration = checkNotNull(registrationId)
                val stateEpoch = checkNotNull(epoch)
                val key = "$id/$stateEpoch/$registration"
                val previous = database.shared().workspace(key)
                database.shared().quarantineOtherRegistrations(registration, id, stateEpoch)
                database.shared().saveWorkspace(previous?.copy(name = name, selected = true)
                    ?: SharedWorkspace(key, id, stateEpoch, registration, name, selected = true))
            }
            lease.check()
        }
        selectedHouseholdValue.value = id
        SharedSyncWorker.request(context, this@AccountData)
    }
    private var controller: VoiceController? = null
    val voice: VoiceController get() = controller ?: VoiceController(context, recordings,
        online = if (identity == null) null else { audio ->
            val app = context.applicationContext as fi.bundo.BunDoApplication
            fi.bundo.speech.authenticatedSpeech {
                kotlinx.coroutines.withTimeout(150_000) {
                    app.withAccountToken(this@AccountData) { token ->
                        fi.bundo.speech.OnlineSpeech().transcribe(token, checkNotNull(registrationId), audio,
                            if (context.resources.configuration.locales[0].language == "fi") "fi-FI" else "en-US")
                    }
                }
            }
        }).also { controller = it }
    internal fun revoke() {
        lease.revoke()
        fi.bundo.reminders.AndroidReminders.stop(context, this)
        scope.cancel()
        controller?.close()
    }
    internal suspend fun close() {
        lease.drain()
        database.close()
    }
}

/** One active account. Opening any retained account requires a newly API-validated identity. */
class AccountStore(private val context: Context, private val name: String = "accounts") {
    val legacyAudio by lazy { LegacyRecordingRecovery(context, name) }
    private val transitions = Mutex()
    private val activationGate = Any()
    private var retiring: AccountData? = null
    private val root = File(context.noBackupFilesDir, name)
    private val master = KeystoreVault("bundo.$name.installation")
    private val activeKey = KeystoreVault("bundo.$name.active")
    private val activeFile get() = File(root, "active")
    private val signOutMarker get() = File(root, "signed-out")
    val credentialsNeedRemoval: Boolean get() = signOutMarker.exists()
    private val mutable = MutableStateFlow<AccountData?>(null)
    val active = mutable.asStateFlow()
    val authentication = VerifiedSession()
    val installationId: String
    val unexpectedFiles: Boolean

    init {
        val proof = File(root, "installation")
        val recovered = runCatching {
            JSONObject(String(master.read(proof, name))).getString("installationId")
                .also { UUID.fromString(it) }
        }.getOrNull()
        val needsQuarantine = recovered == null && (root.exists() ||
            (name == "accounts" && context.getDatabasePath(InboxDatabase.FILE_NAME).exists()))
        unexpectedFiles = needsQuarantine || retainedDirectories().isNotEmpty()
        if (recovered == null) {
            if (needsQuarantine) {
                val retained = File(context.noBackupFilesDir, "$name-unexpected-${UUID.randomUUID()}")
                if (root.exists()) check(root.renameTo(retained)) else retained.mkdirs()
                if (name == "accounts") {
                    for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                        val source = context.getDatabasePath(InboxDatabase.FILE_NAME + suffix)
                        if (source.exists()) check(source.renameTo(File(retained, source.name)))
                    }
                    val audio = File(context.noBackupFilesDir, "anonymous-audio")
                    if (audio.exists()) check(audio.renameTo(File(retained, "anonymous-audio")))
                }
            }
            activeKey.destroy()
            master.destroy()
            master.create()
            installationId = UUID.randomUUID().toString()
            master.write(proof, name, JSONObject().put("installationId", installationId).toString().toByteArray())
        } else installationId = recovered
        val restored = runCatching {
            check(!credentialsNeedRemoval)
            val value = JSONObject(String(activeKey.read(activeFile, installationId)))
            val identity = ValidatedIdentity(value.getString("issuer"), value.getString("subject"))
            openAccount(identity, create = false, registrationId = null)
        }.getOrNull()
        mutable.value = restored ?: anonymous()
        restored?.identity?.let { authentication.accept(authentication.beginSignIn(), it) }
        schedule(mutable.value!!)
    }

    private fun retainedDirectories(): List<File> =
        context.noBackupFilesDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("$name-unexpected-") }
            .sortedBy { it.name }

    private fun accountKey(identity: ValidatedIdentity): String {
        val bytes = JSONObject().put("issuer", identity.issuer).put("subject", identity.subject)
            .toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** A retired server registration needs explicit replacement, never replay of old commands. */
    suspend fun registrationInstallation(identity: ValidatedIdentity, replace: Boolean = false): String =
        transitions.withLock {
            withContext(Dispatchers.IO) {
                val key = accountKey(identity)
                val file = File(root, "$key-registration-installation")
                if (replace) {
                    val next = UUID.randomUUID().toString()
                    master.write(file, "$installationId:$key:registration", next.toByteArray())
                    next
                } else if (file.exists()) {
                    String(master.read(file, "$installationId:$key:registration"))
                        .also(UUID::fromString)
                } else installationId
            }
        }

    private fun anonymous(): AccountData {
        val dbName = if (name == "accounts") InboxDatabase.FILE_NAME else "$name-anonymous.db"
        val database = InboxDatabase.open(context, dbName)
        val lease = DataLease("anonymous-$name")
        return AccountData(null, lease, database,
            RecordingStore(database, File(context.noBackupFilesDir, "$name-anonymous-audio"), lease),
            null, null, context)
    }

    private fun openAccount(identity: ValidatedIdentity, create: Boolean, registrationId: String?): AccountData {
        val key = accountKey(identity)
        val directory = File(root, key)
        val metadata = File(directory, "metadata")
        val value = if (metadata.exists()) JSONObject(String(master.read(metadata, "$installationId:$key"))) else {
            check(create)
            JSONObject().put("issuer", identity.issuer).put("subject", identity.subject)
                .put("key", Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)))
        }
        check(value.getString("issuer") == identity.issuer && value.getString("subject") == identity.subject)
        if (registrationId != null) value.put("registrationId", registrationId)
        val registered = value.getString("registrationId")
        UUID.fromString(registered)
        master.write(metadata, "$installationId:$key", value.toString().toByteArray())
        val secret = Base64.getDecoder().decode(value.getString("key"))
        val database = InboxDatabase.open(context, File(directory, "inbox.db").absolutePath, secret.copyOf())
        val lease = DataLease(key)
        return AccountData(identity, lease, database,
            RecordingStore(database, File(directory, "audio"), lease, secret),
            directory, registered, context)
    }

    /** Caller must carry its authentication generation through both API calls. */
    suspend fun unlock(identity: ValidatedIdentity, registrationId: String, allowed: () -> Boolean = { true }) = transitions.withLock {
        check(allowed()) { "Authentication session ended" }
        UUID.fromString(registrationId)
        withContext(Dispatchers.IO) {
            val next = openAccount(identity, create = true, registrationId)
            try {
                // Force decryption/schema validation before replacing the current account.
                next.database.inbox().allDrafts()
                retireCurrent()
                next.database.withTransaction {
                    next.database.shared().quarantineOtherRegistrations(registrationId)
                }
                synchronized(activationGate) {
                    check(allowed()) { "Authentication session ended" }
                    signOutMarker.delete()
                    activeKey.create()
                    activeKey.write(activeFile, installationId,
                        JSONObject().put("issuer", identity.issuer).put("subject", identity.subject).toString().toByteArray())
                    mutable.value = next
                }
                schedule(next)
            } catch (error: Throwable) {
                next.revoke()
                next.close()
                throw error
            }
        }
    }

    /** Revokes the persisted offline unlock before returning, even with no network. */
    fun lockNow() = synchronized(activationGate) {
        root.mkdirs()
        check(signOutMarker.exists() || signOutMarker.createNewFile())
        activeKey.destroy()
        mutable.value?.let {
            it.revoke()
            WorkManager.getInstance(context).cancelAllWorkByTag("account:${it.lease.owner}")
            retiring = it
        }
        mutable.value = null
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    fun credentialsRemoved() { check(!signOutMarker.exists() || signOutMarker.delete()) }

    suspend fun signOut(delete: Boolean = false) = transitions.withLock {
        lockNow()
        withContext(Dispatchers.IO) {
            val previous = mutable.value ?: retiring
            retireCurrent()
            if (delete && previous?.identity != null) {
                check(previous.directory!!.deleteRecursively())
            }
            activeFile.delete()
            mutable.value = anonymous()
            schedule(mutable.value!!)
        }
    }

    private suspend fun retireCurrent() {
        val previous = mutable.value ?: retiring ?: return
        previous.revoke()
        mutable.value = null
        WorkManager.getInstance(context).cancelAllWorkByTag("account:${previous.lease.owner}")
        context.getSystemService(NotificationManager::class.java).cancelAll()
        previous.close()
        retiring = null
    }

    private fun schedule(data: AccountData) {
        data.startReminders()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "audio-expiry:${data.lease.owner}", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AudioExpiryWorker>(6, TimeUnit.HOURS)
                .addTag("account:${data.lease.owner}")
                .setInputData(workDataOf("owner" to data.lease.owner, "generation" to data.lease.generation))
                .build())
        if (data.identity != null) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "shared-periodic:${data.lease.owner}", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SharedSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag("account:${data.lease.owner}")
                    .setInputData(workDataOf("owner" to data.lease.owner, "generation" to data.lease.generation))
                    .build())
            SharedSyncWorker.request(context, data)
        }
    }

    fun matching(owner: String?, generation: String?): AccountData? =
        active.value?.takeIf { it.lease.active && it.lease.owner == owner && it.lease.generation == generation }

    suspend fun close() = transitions.withLock { withContext(Dispatchers.IO) { retireCurrent() } }

    suspend fun recovery(data: AccountData): List<RecoveryText> = data.lease.access {
        data.database.withTransaction {
            data.database.inbox().allTasks().map {
                RecoveryText("task:${it.id}", it.title, it.description, it.createdAt)
            } + data.database.inbox().allDrafts().filter { it.title.isNotBlank() || it.description.isNotBlank() }.map {
                RecoveryText("draft:${it.key}", it.title, it.description, it.savedAt)
            } + data.database.shared().recovery().map {
                RecoveryText("shared:${it.scope}:${it.sequence}", it.title, it.description.orEmpty(),
                    runCatching { java.time.Instant.parse(JSONObject(it.captureContext).getString("capturedInstant")).toEpochMilli() }.getOrDefault(0),
                    workspaceLabel = data.database.shared().workspace(it.scope)?.name,
                    reason = it.problem, captureContext = it.captureContext)
            } + data.database.shared().allDrafts().flatMap {
                SharedSplitPreview.recovery(it, data.database.shared().workspace(it.scope)?.name)
            }
        }
    }

    /** Preview only. The UI must select text before a new household command is allocated. */
    suspend fun sharedImportPreview(data: AccountData): List<RecoveryText> = recovery(data)

    suspend fun anonymousPreview(data: AccountData): List<RecoveryText> = data.lease.access {
        check(data.identity != null)
        val sources = mutableListOf((if (name == "accounts") InboxDatabase.FILE_NAME else "$name-anonymous.db") to "")
        // The pre-identity shell had no account owner. Its quarantined plaintext inbox
        // can be explicitly copied after sign-in, never attached or replayed automatically.
        for (directory in retainedDirectories()) {
            val legacy = File(directory, InboxDatabase.FILE_NAME)
            if (legacy.exists()) sources += legacy.absolutePath to "${directory.name}:"
        }
        sources.flatMap { (path, prefix) ->
            val database = InboxDatabase.open(context, path)
            try {
                database.inbox().allTasks().map { RecoveryText("${prefix}task:${it.id}", it.title, it.description, it.createdAt) } +
                    database.inbox().allDrafts().filter { it.title.isNotBlank() || it.description.isNotBlank() }
                        .map { RecoveryText("${prefix}draft:${it.key}", it.title, it.description, it.savedAt) }
            } finally { database.close() }
        }
    }

    /** Copy selected text only. Source stays intact; no old intent/sequence is replayed. */
    suspend fun importAnonymous(data: AccountData, selected: Set<String>) {
        val source = anonymousPreview(data).filter { it.source in selected }
        importText(data, source)
    }

    suspend fun importText(data: AccountData, source: List<RecoveryText>) {
        data.lease.access {
            data.database.withTransaction {
                for (text in source) {
                    require(InboxLimits.valid(text.title, text.description))
                    val id = UUID.randomUUID().toString()
                    data.database.inbox().insertTask(InboxTask(id, text.title, text.description,
                        text.title, text.description, text.capturedAt, System.currentTimeMillis()))
                    data.database.inbox().insertIntent(InboxIntent(taskId = id, kind = "CaptureInboxTask",
                        title = text.title, description = text.description, createdAt = text.capturedAt))
                }
                data.lease.check()
            }
        }
    }
}

data class RecoveryText(val source: String, val title: String, val description: String, val capturedAt: Long,
    val workspaceLabel: String? = null, val reason: String? = null, val captureContext: String? = null)
