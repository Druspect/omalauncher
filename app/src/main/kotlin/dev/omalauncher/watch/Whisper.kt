package dev.omalauncher.watch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Offline speech-to-text for OmaWatch voice (omawatch D14): whisper.cpp built into this
 * app, with a model the owner downloads once from the widget. Audio never leaves the phone.
 */
class Whisper(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "whisper")
    private val model = File(dir, MODEL)
    private val _state = MutableStateFlow(if (model.isFile) "ready" else "not downloaded")
    val state: StateFlow<String> = _state.asStateFlow()

    private var handle = 0L

    val ready get() = model.isFile

    /** Downloads and checks the model. Blocking; call off the main thread. */
    fun download() {
        if (model.isFile) return
        dir.mkdirs()
        val part = File(dir, "$MODEL.part")
        _state.value = "downloading"
        val ok = runCatching {
            val connection = URL(URL_MODEL).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val total = connection.contentLengthLong
            val sha1 = MessageDigest.getInstance("SHA-1")
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    var shown = -1L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sha1.update(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) done * 100 / total else -1
                        if (percent != shown) {
                            shown = percent
                            _state.value = if (percent >= 0) "downloading $percent%" else "downloading"
                        }
                    }
                }
            }
            sha1.digest().joinToString("") { "%02x".format(it) } == SHA1_MODEL && part.renameTo(model)
        }.getOrDefault(false)
        if (!ok) part.delete()
        _state.value = if (ok) "ready" else "download failed"
    }

    /** 16 kHz mono PCM16LE in, text out. Null when the model is missing or whisper fails. */
    @Synchronized
    fun transcribe(pcm: File): String? {
        if (!model.isFile) return null
        if (handle == 0L) {
            System.loadLibrary("omawhisper")
            handle = nativeInit(model.absolutePath)
            if (handle == 0L) return null
        }
        val samples = floats(pcm.readBytes())
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        return nativeTranscribe(handle, samples, threads)?.trim()
    }

    private external fun nativeInit(path: String): Long

    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, threads: Int): String?

    private external fun nativeFree(handle: Long)

    companion object {
        /** whisper.cpp's English base model. SHA-1 from whisper.cpp v1.9.5 models/README.md. */
        const val MODEL = "ggml-base.en.bin"
        const val SHA1_MODEL = "137c40403d78fd54d454da0f9bd998f78703390c"
        const val URL_MODEL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"

        fun floats(pcm16le: ByteArray): FloatArray {
            val shorts = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        }
    }
}
