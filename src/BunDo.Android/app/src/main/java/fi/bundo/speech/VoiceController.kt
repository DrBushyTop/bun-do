package fi.bundo.speech

import android.content.Context
import android.net.Uri
import android.os.Build
import fi.bundo.data.InboxLimits
import fi.bundo.data.ModelInstaller
import fi.bundo.data.SpeechStorageFull
import fi.bundo.data.RecordingStorageFull
import fi.bundo.data.RecordingStore
import fi.bundo.data.VoiceRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

data class VoiceState(
    val loaded: Boolean = false,
    val modelReady: Boolean = false,
    val phase: String = "CHECKING",
    val progress: Float = 0f,
    val seconds: Int = 0,
    val level: Float = 0f,
    val message: String = "",
    val savedTaskId: String? = null,
    val recordings: List<VoiceRecording> = emptyList(),
) {
    val busy: Boolean get() = phase != "IDLE"
}

/** App-owned work survives rotation. Process death is recovered from Room and PCM files. */
class VoiceController(
    private val context: Context,
    private val store: RecordingStore,
    private val installer: ModelInstaller = ModelInstaller(File(context.noBackupFilesDir, "speech-models"), loadSpeechManifest(context)),
    private val transcribe: (File, File) -> String = LocalSpeech()::transcribe,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(VoiceState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var recorder: LocalRecorder? = null
    private var stopReason = ""
    private var stopRequested = false
    private var verifiedModel: File? = null

    init {
        scope.launch {
            try {
                store.recordings.collect { rows -> mutable.update { it.copy(recordings = rows) } }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { it.copy(message = "RECOVERY_FAILED") }
            }
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.recover()
                    installer.recover()
                    verifiedModel = installer.verifiedActive()
                }
                mutable.update { it.copy(loaded = true, phase = "IDLE", modelReady = verifiedModel != null) }
            } catch (_: Exception) {
                mutable.update { it.copy(loaded = true, phase = "IDLE", message = "RECOVERY_FAILED") }
            }
        }
        scope.launch {
            while (true) {
                delay(60_000)
                if (!state.value.busy) {
                    try { withContext(Dispatchers.IO) { store.prune() } }
                    catch (_: Exception) { mutable.update { it.copy(message = "CLEANUP_FAILED") } }
                }
            }
        }
    }

    fun permissionDenied() { mutable.update { it.copy(message = "PERMISSION") } }
    fun acknowledgeSaved() { mutable.update { it.copy(savedTaskId = null) } }

    fun install() = start("INSTALLING") {
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) error("Unsupported speech ABI")
        val cancellation = coroutineContext
        val model = withContext(Dispatchers.IO) {
            installer.install(
                source = { openDownload(installer.manifest.url) },
                checkCancelled = { cancellation.ensureActive() },
                progress = { current, total ->
                    mutable.update { it.copy(progress = current.toFloat() / total) }
                },
            )
        }
        verifiedModel = model
        mutable.update { it.copy(modelReady = true, message = "INSTALLED") }
    }

    fun record() {
        if (!state.value.modelReady || state.value.busy) return
        stopReason = ""
        stopRequested = false
        start("RECORDING") {
            val row = withContext(Dispatchers.IO) { store.begin() }
            val input = LocalRecorder()
            recorder = input
            if (stopRequested) input.stop()
            try {
                withContext(Dispatchers.IO) {
                    input.record(store.audio(row.id)) { seconds, level ->
                        mutable.update { it.copy(seconds = seconds, level = level) }
                    }
                }
                recorder = null
                if (stopReason.isNotEmpty()) {
                    withContext(Dispatchers.IO) { store.failed(row.id, stopReason) }
                    mutable.update { it.copy(message = stopReason) }
                } else recognize(row.id)
            } catch (error: Exception) {
                recorder = null
                withContext(Dispatchers.IO) { store.failed(row.id, "RECORDING_FAILED") }
                throw error
            }
        }
    }

    fun stop() { stopRequested = true; recorder?.stop() }

    fun interruptRecording() {
        if (state.value.phase == "RECORDING") {
            stopReason = "INTERRUPTED"
            stop()
        }
    }

    fun cancel() {
        if (state.value.phase == "RECORDING") {
            stopReason = "CANCELED"
            stop()
        } else work?.cancel()
    }

    fun retry(id: String) = start("TRANSCRIBING") { recognize(id) }
    fun delete(id: String) = start("CLEANING") { withContext(Dispatchers.IO) { store.delete(id) } }

    fun export(id: String, destination: Uri) = start("EXPORTING") {
        withContext(Dispatchers.IO) {
            check(store.available(id))
            checkNotNull(context.contentResolver.openOutputStream(destination, "w")).use {
                exportWave(store.audio(id), it)
            }
        }
        mutable.update { it.copy(message = "EXPORTED") }
    }

    private suspend fun recognize(id: String) {
        mutable.update { it.copy(phase = "TRANSCRIBING", level = 0f) }
        try {
            withContext(Dispatchers.IO) {
                check(store.available(id))
                store.transcribing(id)
                val model = checkNotNull(verifiedModel)
                val text = transcribe(model, store.audio(id))
                coroutineContext.ensureActive() // A late JNI result cannot commit after cancel.
                when {
                    text.isBlank() -> store.failed(id, "SILENCE")
                    InboxLimits.length(text) > InboxLimits.DESCRIPTION -> store.failed(id, "TOO_LONG")
                    else -> {
                        val saved = store.commit(id, text)
                        mutable.update { it.copy(savedTaskId = saved) }
                    }
                }
                mutable.update { it.copy(message = when {
                    text.isBlank() -> "SILENCE"
                    InboxLimits.length(text) > InboxLimits.DESCRIPTION -> "TOO_LONG"
                    else -> "SAVED"
                }) }
            }
        } catch (error: Throwable) {
            // JNI decode cannot be interrupted. Only its result is canceled; retain audio.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                store.failed(id, if (error is CancellationException) "CANCELED" else "TRANSCRIPTION_FAILED")
            }
            throw error
        }
    }

    private fun start(phase: String, operation: suspend () -> Unit) {
        if (!state.value.loaded || state.value.busy) return
        mutable.update { it.copy(phase = phase, progress = 0f, seconds = 0, message = "") }
        work = scope.launch {
            try { operation() }
            catch (_: CancellationException) { mutable.update { it.copy(message = "CANCELED") } }
            catch (_: SpeechStorageFull) { mutable.update { it.copy(message = "MODEL_SPACE") } }
            catch (_: RecordingStorageFull) { mutable.update { it.copy(message = "AUDIO_SPACE") } }
            catch (_: Exception) { mutable.update { it.copy(message = "FAILED") } }
            catch (_: LinkageError) { mutable.update { it.copy(message = "FAILED") } }
            finally { mutable.update { it.copy(phase = "IDLE", level = 0f) } }
        }
    }

    private fun openDownload(url: String): InputStream {
        var uri = URI(url)
        repeat(6) {
            check(uri.scheme == "https" && uri.host in setOf("github.com", "release-assets.githubusercontent.com"))
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (connection.responseCode in 300..399) {
                val location = checkNotNull(connection.getHeaderField("Location"))
                connection.disconnect()
                uri = uri.resolve(location)
            } else {
                if (connection.responseCode != 200) {
                    connection.disconnect()
                    error("Model download failed")
                }
                return object : FilterInputStream(connection.inputStream) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            }
        }
        error("Too many model redirects")
    }

    override fun close() {
        recorder?.stop()
        scope.cancel()
    }
}
