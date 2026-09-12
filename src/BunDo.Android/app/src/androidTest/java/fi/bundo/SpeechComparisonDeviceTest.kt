package fi.bundo

import android.content.Intent
import android.media.AudioFormat
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.ModelInstaller
import fi.bundo.data.RecordingStore
import fi.bundo.speech.LocalSpeech
import fi.bundo.speech.loadSpeechManifest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An experiment, not a quality acceptance test. Default runs skip it.
 * No downloads, cloud fallback, reference-text hints or transcript logging.
 * Inputs and results live in private cache and the host runner removes them.
 */
@RunWith(AndroidJUnit4::class)
class SpeechComparisonDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun comparePrerecordedSpeechOffline() {
        val engine = InstrumentationRegistry.getArguments().getString("speechComparison")
        assumeTrue(engine in setOf("native", "parakeet"))
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        check(context.getSystemService(ConnectivityManager::class.java).allNetworks.isEmpty()) {
            "Disconnect every emulator network before copying audio or running this experiment"
        }
        val input = File(context.cacheDir, "speech-comparison")
        val report = File(context.cacheDir, "speech-comparison-results.json")
        val results = JSONArray()
        val metadata = JSONObject().put("engine", engine).put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT).put("results", results)
            .put("corpusSha256", InstrumentationRegistry.getArguments().getString("speechCorpusSha256"))
        if (engine == "parakeet") {
            metadata.put("model", JSONObject(context.assets.open("speech/parakeet-v3.json")
                .bufferedReader().use { it.readText() }))
        } else {
            val services = context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
            metadata.put("providers", JSONArray(services.map {
                val info = context.packageManager.getPackageInfo(it.serviceInfo.packageName, 0)
                JSONObject().put("package", info.packageName).put("version", info.versionName)
                    .put("versionCode", info.longVersionCode).put("service", it.serviceInfo.name)
            }))
        }
        try {
            val nativeAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            metadata.put("nativeAvailable", nativeAvailable)
            if (engine == "native") {
                val support = JSONObject()
                if (nativeAvailable) {
                    for (language in listOf("fi-FI", "en-US")) support.put(language, nativeSupport(language))
                }
                metadata.put("support", support)
            }
            report.writeText(metadata.toString())
            val manifest = File(input, "manifest.json")
            if (!manifest.exists()) return // Availability-only probe.
            val cases = JSONArray(manifest.readText())
            val model = if (engine == "parakeet") {
                ModelInstaller(File(context.noBackupFilesDir, "speech-models"), loadSpeechManifest(context))
                    .verifiedActive().also { checkNotNull(it) { "Install pinned Parakeet model first" } }
            } else null
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val id = case.getString("id")
                check(id.matches(Regex("[a-z0-9-]+")))
                val audio = File(input, "$id.pcm")
                check(audio.isFile && audio.length() in 2..RecordingStore.MAX_AUDIO_BYTES && audio.length() % 2 == 0L)
                val started = SystemClock.elapsedRealtime()
                val result = if (engine == "native") {
                    if (!nativeAvailable) JSONObject().put("status", "unavailable")
                    else recognizeNative(audio, case.getString("language"))
                } else {
                    JSONObject().put("status", "result")
                        .put("text", LocalSpeech().transcribe(checkNotNull(model), audio))
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                val hash = MessageDigest.getInstance("SHA-256").digest(audio.readBytes())
                    .joinToString("") { "%02x".format(it) }
                results.put(result.put("id", id).put("elapsedMs", elapsed)
                    .put("audioSha256", hash).put("audioSeconds", audio.length().toDouble() / 32_000)
                    .put("latencyKind", if (engine == "native") "file-input-start-to-final" else "decode-including-model-load"))
                report.writeText(metadata.toString())
            }
        } finally {
            report.writeText(metadata.toString())
            input.deleteRecursively()
        }
    }

    private fun intent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        .putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, false)

    private fun nativeSupport(language: String): JSONObject {
        val result = CompletableFuture<JSONObject>()
        var recognizer: SpeechRecognizer? = null
        try {
            main {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer!!.checkRecognitionSupport(intent(language), context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            result.complete(JSONObject().put("status", "result")
                                .put("installed", JSONArray(support.installedOnDeviceLanguages))
                                .put("supported", JSONArray(support.supportedOnDeviceLanguages))
                                .put("pending", JSONArray(support.pendingOnDeviceLanguages)))
                        }
                        override fun onError(error: Int) {
                            result.complete(JSONObject().put("status", "error").put("code", error))
                        }
                    })
            }
            return await(result, 15)
        } finally {
            main { recognizer?.destroy() }
        }
    }

    private fun recognizeNative(audio: File, language: String): JSONObject {
        val result = CompletableFuture<JSONObject>()
        var recognizer: SpeechRecognizer? = null
        val segments = mutableListOf<String>()
        ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            try {
                main {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    recognizer!!.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onError(error: Int) {
                            result.complete(JSONObject().put("status", "error").put("code", error))
                        }
                        override fun onResults(results: Bundle?) {
                            result.complete(JSONObject().put("status", "result").put("text", text(results)))
                        }
                        override fun onSegmentResults(segmentResults: Bundle) {
                            segments.add(text(segmentResults))
                        }
                        override fun onEndOfSegmentedSession() {
                            result.complete(JSONObject().put("status", "result").put("text", segments.joinToString(" ")))
                        }
                    })
                    recognizer!!.startListening(intent(language)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RecordingStore.SAMPLE_RATE)
                        .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE))
                }
                return await(result, 60)
            } finally {
                main { recognizer?.cancel(); recognizer?.destroy() }
            }
        }
    }

    private fun text(bundle: Bundle?) =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun await(result: CompletableFuture<JSONObject>, seconds: Long): JSONObject = try {
        result.get(seconds, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        JSONObject().put("status", "timeout")
    }
}
