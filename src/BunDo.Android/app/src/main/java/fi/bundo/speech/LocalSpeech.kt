package fi.bundo.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import fi.bundo.data.RecordingStore
import fi.bundo.data.ModelManifest
import fi.bundo.data.ModelFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

fun loadSpeechManifest(context: Context): ModelManifest {
    val json = JSONObject(context.assets.open("speech/parakeet-v3.json").bufferedReader().use { it.readText() })
    val files = json.getJSONArray("files")
    return ModelManifest(
        json.getString("id"), json.getString("url"), json.getLong("archiveBytes"),
        json.getString("sha256"), json.getString("prefix"),
        (0 until files.length()).map {
            val file = files.getJSONObject(it)
            ModelFile(file.getString("name"), file.getLong("bytes"), file.getString("sha256"), file.getBoolean("install"))
        },
    )
}

/** Never owns an HTTP client. Only 16 kHz signed little-endian mono PCM enters JNI. */
class LocalSpeech {
    fun transcribe(model: File, audio: File): String {
        require(audio.length() <= RecordingStore.MAX_AUDIO_BYTES)
        return transcribe(model, audio.readBytes())
    }
    fun transcribe(model: File, bytes: ByteArray): String {
        require(bytes.size <= RecordingStore.MAX_AUDIO_BYTES)
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
        if (samples.isEmpty() || samples.all { abs(it) < 0.001f }) return ""
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RecordingStore.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(model, "encoder.int8.onnx").path,
                    decoder = File(model, "decoder.int8.onnx").path,
                    joiner = File(model, "joiner.int8.onnx").path,
                ),
                tokens = File(model, "tokens.txt").path, numThreads = 2,
                debug = false, provider = "cpu", modelType = "nemo_transducer",
            ),
        ))
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, RecordingStore.SAMPLE_RATE)
                recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            } finally { stream.release() }
        } finally { recognizer.release() }
    }
}

/** Blocking recorder, called on IO. Persist raw PCM so process death needs no header repair. */
interface RecordingInput {
    fun stop()
    fun record(output: OutputStream, progress: (Int, Float) -> Unit)
}

class LocalRecorder : RecordingInput {
    @Volatile private var stopping = false
    override fun stop() { stopping = true }

    @SuppressLint("MissingPermission") // Caller requests permission; AudioRecord failure is still handled.
    override fun record(output: OutputStream, progress: (Int, Float) -> Unit) {
        val rate = RecordingStore.SAMPLE_RATE
        val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            output.use {
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val buffer = ShortArray(2048)
                val bytes = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                var samples = 0L
                while (!stopping && samples < rate * RecordingStore.MAX_SECONDS) {
                    val count = recorder.read(buffer, 0, minOf(buffer.size, (rate * RecordingStore.MAX_SECONDS - samples).toInt()))
                    check(count > 0)
                    bytes.clear()
                    var peak = 0f
                    for (i in 0 until count) {
                        bytes.putShort(buffer[i])
                        peak = maxOf(peak, abs(buffer[i] / 32768f))
                    }
                    output.write(bytes.array(), 0, count * 2)
                    samples += count
                    val seconds = (samples / rate).toInt()
                    progress(seconds, peak)
                }
                output.flush()
            }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally { recorder.release() }
        }
    }
}

fun exportWave(audio: File, output: OutputStream) {
    require(audio.length() <= RecordingStore.MAX_AUDIO_BYTES)
    exportWave(audio.readBytes(), output)
}

fun exportWave(audio: ByteArray, output: OutputStream, checkActive: () -> Unit = {}) {
    val length = audio.size.toLong().let { it - it % 2 }
    require(length <= RecordingStore.MAX_AUDIO_BYTES)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt((length + 36).toInt()).put("WAVEfmt ".toByteArray())
    header.putInt(16).putShort(1).putShort(1).putInt(RecordingStore.SAMPLE_RATE)
    header.putInt(RecordingStore.SAMPLE_RATE * 2).putShort(2).putShort(16)
    header.put("data".toByteArray()).putInt(length.toInt())
    checkActive()
    output.write(header.array())
    audio.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            checkActive()
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            check(read > 0)
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}
