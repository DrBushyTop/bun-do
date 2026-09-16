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
import fi.bundo.data.SavedRecording
import fi.bundo.data.VoiceDraft
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

data class ReviewUse(val id: String, val target: fi.bundo.data.VoiceTarget?, val draft: VoiceDraft)

data class VoiceState(
    val loaded: Boolean = false,
    val modelReady: Boolean = false,
    val onlineConfigured: Boolean = false,
    val usingOnline: Boolean = false,
    val phase: String = "CHECKING",
    val progress: Float = 0f,
    val seconds: Int = 0,
    val level: Float = 0f,
    val message: String = "",
    val saved: SavedRecording? = null,
    val recordings: List<VoiceRecording> = emptyList(),
    val reviewId: String? = null,
    val reviewTarget: fi.bundo.data.VoiceTarget? = null,
    val draft: VoiceDraft? = null,
    val reviewUse: ReviewUse? = null,
    val localOnly: Boolean = false,
    val keepAudio: Boolean = false,
    val analyze: Boolean = true,
    val analysisConfigured: Boolean = false,

) {
    val busy: Boolean get() = phase != "IDLE"
    val savedTaskId: String? get() = saved?.taskId
}

/** App-owned work survives rotation. Process death is recovered from Room and PCM files. */
class VoiceController(
    private val context: Context,
    private val store: RecordingStore,
    private val installer: ModelInstaller = ModelInstaller(File(context.noBackupFilesDir, "speech-models"), loadSpeechManifest(context)),
    private val online: (suspend (ByteArray) -> String)? = null,
    private val connected: () -> Boolean = { speechNetworkAvailable(context) },
    private val recorderFactory: () -> RecordingInput = ::LocalRecorder,
    private val analyze: (suspend (String) -> VoiceDraft)? = null,
    private val preferences: VoicePreferences = VoicePreferences(context),
    private val transcribe: (File, ByteArray) -> String = LocalSpeech()::transcribe,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(VoiceState(onlineConfigured = online != null, analysisConfigured = analyze != null, localOnly = preferences.localOnly, keepAudio = preferences.keepAudio, analyze = preferences.analyze))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var reviewWrite: Job? = null
    private var pendingFallback: Job? = null
    private var recorder: RecordingInput? = null
    private var stopReason = ""
    private var stopRequested = false
    private var verifiedModel: File? = null
    private var activeRecording: String? = null

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

    fun configure(localOnly: Boolean = state.value.localOnly, keepAudio: Boolean = state.value.keepAudio, analyze: Boolean = state.value.analyze) {
        if (state.value.busy) return
        preferences.localOnly = localOnly
        preferences.keepAudio = keepAudio
        preferences.analyze = analyze
        mutable.update { it.copy(localOnly = localOnly, keepAudio = keepAudio, analyze = analyze) }
    }
    fun clearMessage() { mutable.update { it.copy(message = "") } }
    fun closeReview() { mutable.update { it.copy(reviewId = null, draft = null, reviewUse = null) } }
    fun openReview(id: String) = start("LOADING") {
        reviewWrite?.join()
        val row = withContext(Dispatchers.IO) { checkNotNull(store.get(id)) }
        check(row.state == "REVIEW")
        mutable.update { it.copy(reviewId = id, reviewTarget = row.workspaceScope?.let { scope -> fi.bundo.data.VoiceTarget(scope) }, draft = VoiceDraft.parse(checkNotNull(row.review))) }
    }
    /** Enqueue on Main before returning to Compose. The app-owned ordered chain
     * survives dismissal/rotation; a later snapshot can never write before an older one. */
    fun editReview(id: String, draft: VoiceDraft) {
        if (state.value.reviewId != id || state.value.busy) return
        mutable.update { it.copy(draft = draft) }
        val previous = reviewWrite
        reviewWrite = scope.launch {
            previous?.join()
            try { withContext(Dispatchers.IO) { store.review(id, draft) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.update { it.copy(message = "DRAFT_FAILED") } }
        }
    }
    fun saveReview(id: String, draft: VoiceDraft) = start("SAVING") {
        reviewWrite?.join()
        withContext(Dispatchers.IO) {
            store.review(id, draft)
            mutable.update { it.copy(draft = draft) }
            val saved = store.commit(id, draft.transcript, draft)
            mutable.update { it.copy(saved = saved, reviewId = null, draft = null, message = "SAVED") }
        }
    }
    fun useReview(id: String, draft: VoiceDraft) = start("SAVING") {
        check(state.value.reviewId == id)
        reviewWrite?.join()
        val row = withContext(Dispatchers.IO) { store.prepareReviewUse(id, draft) }
        mutable.update { it.copy(draft = draft, reviewUse = ReviewUse(id, row.workspaceScope?.let { scope -> fi.bundo.data.VoiceTarget(scope) }, draft)) }
    }
    fun acknowledgeReviewUse(id: String) = start("SAVING") {
        val result = checkNotNull(state.value.reviewUse).also { check(it.id == id) }
        withContext(Dispatchers.IO) { store.useReview(id, result.draft) }
        closeReview()
    }

    fun permissionDenied() { mutable.update { it.copy(message = "PERMISSION") } }
    fun acknowledgeSaved() { mutable.update { it.copy(saved = null) } }

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

    fun record(target: fi.bundo.data.VoiceTarget? = null, review: Boolean = false) {
        if (state.value.busy) return
        if (!state.value.modelReady && (online == null || state.value.localOnly || !connected())) {
            mutable.update { it.copy(message = "OFFLINE_MODEL_REQUIRED") }
            return
        }
        closeReview()
        val keepAudio = state.value.keepAudio
        stopReason = ""
        stopRequested = false
        start("RECORDING") {
            val row = withContext(Dispatchers.IO) { store.begin(target = target, keepAudio = keepAudio, reviewRequired = review || target?.taskId != null) }
            val input = recorderFactory()
            recorder = input
            if (stopRequested) input.stop()
            try {
                withContext(Dispatchers.IO) {
                    store.output(row.id).use { output ->
                        input.record(output) { seconds, level ->
                            mutable.update { it.copy(seconds = seconds, level = level) }
                        }
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
        pendingFallback?.cancel()
        if (state.value.phase == "RECORDING") {
            stopReason = "CANCELED"
            stop()
        } else work?.cancel()
    }

    fun retry(id: String) = start("TRANSCRIBING") { recognize(id) }
    fun retryOffline(id: String) = start("TRANSCRIBING") { recognize(id, offlineOnly = true) }
    fun useOffline() {
        if (!state.value.usingOnline || !state.value.modelReady) return
        val id = activeRecording ?: return
        val previous = work ?: return
        previous.cancel()
        pendingFallback?.cancel()
        pendingFallback = scope.launch {
            previous.join()
            retryOffline(id)
        }
    }
    fun delete(id: String) = start("CLEANING") { reviewWrite?.join(); withContext(Dispatchers.IO) { store.delete(id) }; if (state.value.reviewId == id) closeReview() }

    fun export(id: String, destination: Uri) = start("EXPORTING") {
        withContext(Dispatchers.IO) {
            check(store.exportable(id))
            checkNotNull(context.contentResolver.openOutputStream(destination, "w")).use {
                val job = coroutineContext
                exportWave(store.readAudio(id), it) { job.ensureActive(); store.checkActive() }
            }
        }
        mutable.update { it.copy(message = "EXPORTED") }
    }

    private suspend fun recognize(id: String, offlineOnly: Boolean = false) {
        activeRecording = id
        mutable.update { it.copy(phase = "TRANSCRIBING", level = 0f) }
        try {
            withContext(Dispatchers.IO) {
                check(store.available(id))
                store.transcribing(id)
                val audio = store.readAudio(id)
                val text = try {
                    if (!offlineOnly && !state.value.localOnly && online != null && connected()) {
                        mutable.update { it.copy(usingOnline = true) }
                        try { online.invoke(audio) }
                        catch (error: Exception) {
                            coroutineContext.ensureActive()
                            // Auth failure remains explicit; local retry is still available.
                            if (error is SpeechFailure && error.code in setOf("SIGN_IN_REQUIRED", "TOO_LONG")) throw error
                            val model = verifiedModel ?: throw SpeechFailure("ONLINE_FAILED")
                            mutable.update { it.copy(usingOnline = false) }
                            transcribe(model, audio)
                        }
                    } else {
                        val model = verifiedModel ?: throw SpeechFailure("OFFLINE_MODEL_REQUIRED")
                        transcribe(model, audio)
                    }
                } finally { audio.fill(0) }
                coroutineContext.ensureActive() // A late JNI result cannot commit after cancel.
                when {
                    text.isBlank() -> store.failed(id, "SILENCE")
                    InboxLimits.length(text) > InboxLimits.DESCRIPTION -> store.failed(id, "TOO_LONG")
                    else -> {
                        val row = checkNotNull(store.get(id))
                        if (row.reviewRequired && row.checklistTaskId == null) {
                            var draft = VoiceDraft.from(text.trim())
                            store.review(id, draft) // Persist text before analysis, and remove unretained audio.
                            mutable.update { it.copy(reviewId = id, reviewTarget = row.workspaceScope?.let { scope -> fi.bundo.data.VoiceTarget(scope) }, draft = draft) }
                            if (row.workspaceScope != null && analyze != null && state.value.analyze && connected()) {
                                mutable.update { it.copy(phase = "ANALYZING") }
                                try {
                                    draft = analyze.invoke(text).copy(transcript = text)
                                    require(draft.valid)
                                    coroutineContext.ensureActive()
                                    store.review(id, draft)
                                    mutable.update { it.copy(draft = draft) }
                                } catch (error: Exception) {
                                    coroutineContext.ensureActive()
                                    mutable.update { it.copy(message = "ANALYSIS_FAILED") }
                                }
                            }
                        } else {
                            val saved = store.commit(id, text)
                            mutable.update { it.copy(saved = saved) }
                        }
                    }
                }
                mutable.update { it.copy(message = when {
                    text.isBlank() -> "SILENCE"
                    InboxLimits.length(text) > InboxLimits.DESCRIPTION -> "TOO_LONG"
                    state.value.reviewId != null -> state.value.message
                    else -> "SAVED"
                }) }
            }
        } catch (error: Throwable) {
            // JNI decode cannot be interrupted. Cancel its result and apply the recorded retention choice.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try { store.failed(id, when (error) {
                    is CancellationException -> "CANCELED"
                    is SpeechFailure -> error.code
                    is fi.bundo.data.TranscriptTooLong -> "TOO_LONG"
                    else -> "TRANSCRIPTION_FAILED"
                }) }
                catch (_: CancellationException) { /* Revoked accounts retain interrupted audio for recovery. */ }
            }
            throw error
        } finally {
            activeRecording = null
            mutable.update { it.copy(usingOnline = false) }
        }
    }

    private fun start(phase: String, operation: suspend () -> Unit) {
        if (!state.value.loaded || state.value.busy) return
        mutable.update { it.copy(phase = phase, progress = 0f, seconds = 0, message = "") }
        work = scope.launch {
            try { operation() }
            catch (_: CancellationException) { mutable.update { it.copy(message = "CANCELED") } }
            catch (_: SpeechStorageFull) { mutable.update { it.copy(message = "MODEL_SPACE") } }
            catch (_: fi.bundo.data.TranscriptTooLong) { mutable.update { it.copy(message = "TOO_LONG") } }
            catch (_: RecordingStorageFull) { mutable.update { it.copy(message = "AUDIO_SPACE") } }
            catch (error: SpeechFailure) { mutable.update { it.copy(message = error.code) } }
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
