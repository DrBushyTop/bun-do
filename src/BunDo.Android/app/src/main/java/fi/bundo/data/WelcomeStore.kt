package fi.bundo.data

import android.content.Context
import fi.bundo.household.InvitationLink
import fi.bundo.identity.ValidatedIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class WelcomeStep { START, FAMILY, CREATE, JOIN, HOME }

/** Private setup data, never a saved-instance-state bundle or a backup preference. */
internal data class WelcomeProgress(
    val visible: Boolean = true,
    val step: WelcomeStep = WelcomeStep.START,
    val name: String = "",
    val displayName: String = "",
    val link: String = "",
    val homeId: String = "",
    val createId: String = UUID.randomUUID().toString(),
    val owner: String = "",
)

/** App-owned ordered writes survive screen dismissal, rotation and account transitions. */
internal class WelcomeStore(
    context: Context,
    name: String = "welcome",
    existingInstall: Boolean = hasExistingInstallation(context),
) : AutoCloseable {
    private val file = File(context.noBackupFilesDir, "$name/state")
    private val vault = KeystoreVault("bundo.$name")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var write: Job? = null
    private val failed = MutableStateFlow(false)
    private val mutable = MutableStateFlow(load(existingInstall))
    val state = mutable.asStateFlow()
    val writeFailed = failed.asStateFlow()

    private fun load(existing: Boolean): WelcomeProgress {
        if (!file.exists()) return WelcomeProgress(visible = !existing).also {
            // Record the first launch before AccountStore creates a database.
            try { persist(it) } catch (_: Exception) { failed.value = true }
        }
        return runCatching {
            val json = JSONObject(String(vault.read(file, "welcome-v1"), Charsets.UTF_8))
            WelcomeProgress(json.getBoolean("visible"), WelcomeStep.valueOf(json.getString("step")),
                json.getString("name"), json.getString("displayName"), json.getString("link"),
                json.getString("homeId"), json.getString("createId"), json.getString("owner"))
        }.getOrElse {
            // Losing setup metadata must never hide existing work or change account storage.
            WelcomeProgress(visible = !existing)
        }
    }

    private fun persist(next: WelcomeProgress) {
        vault.create()
        val json = JSONObject().put("visible", next.visible).put("step", next.step.name)
            .put("name", next.name).put("displayName", next.displayName).put("link", next.link)
            .put("homeId", next.homeId).put("createId", next.createId).put("owner", next.owner)
        vault.write(file, "welcome-v1", json.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun update(change: (WelcomeProgress) -> WelcomeProgress) {
        val next = change(mutable.value)
        mutable.value = next
        val previous = write
        write = scope.launch {
            previous?.join()
            try {
                persist(next)
                failed.value = false
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed.value = true }
        }
    }

    suspend fun awaitSaved() {
        val pending = synchronized(this) { write }
        pending?.join()
        check(!failed.value) { "Setup storage unavailable" }
    }

    fun incoming(value: String) {
        if (InvitationLink.parse(value) == null) return
        update { it.copy(visible = true, step = WelcomeStep.JOIN, link = value.trim(), homeId = "") }
    }

    private fun ownerKey(identity: ValidatedIdentity?): String = identity?.let {
        JSONObject().put("issuer", it.issuer).put("subject", it.subject).toString()
    }.orEmpty()
    fun matches(identity: ValidatedIdentity?): Boolean = state.value.owner.isEmpty() || state.value.owner == ownerKey(identity)
    fun bind(identity: ValidatedIdentity?) {
        val owner = ownerKey(identity)
        if (state.value.owner == owner) return
        update {
            if (it.owner.isEmpty()) it.copy(owner = owner)
            else WelcomeProgress(visible = it.visible, step = WelcomeStep.FAMILY, owner = owner)
        }
    }

    fun reopen() = update { it.copy(visible = true, step = if (it.step == WelcomeStep.START) WelcomeStep.FAMILY else it.step) }
    fun dismiss() = update { it.copy(visible = false) }
    fun accepted(request: WelcomeProgress, homeId: String) = update {
        // A new deep link or account transition owns the screen, not an older network reply.
        if (it == request) it.copy(step = WelcomeStep.HOME, homeId = homeId, link = "") else it
    }
    override fun close() { scope.cancel() }

    companion object {
        /** Called before AccountStore creates an anonymous database on a fresh installation. */
        fun hasExistingInstallation(context: Context): Boolean =
            context.getDatabasePath(InboxDatabase.FILE_NAME).exists() ||
                File(context.noBackupFilesDir, "accounts").exists() ||
                context.noBackupFilesDir.listFiles().orEmpty().any { it.name.startsWith("accounts-unexpected-") }
    }
}
