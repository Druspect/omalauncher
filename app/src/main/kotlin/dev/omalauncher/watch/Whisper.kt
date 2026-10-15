package dev.omalauncher.watch

import android.app.ActivityManager
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

    /**
     * The most accurate model this phone can hold: full large-v3 needs about 3.5 GB while it
     * runs, so a phone with less than 8 GB of RAM gets the q5_0 quantization instead.
     */
    private val choice: Model = run {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        if (memory.totalMem >= 7_500_000_000L) LARGE_V3 else LARGE_V3_Q5
    }
    private val model = File(dir, choice.file)

    /** The model this phone uses and its download size, for the widget. */
    val label get() = "${choice.name} (${choice.size})"
    private val _state = MutableStateFlow(if (model.isFile) "ready" else "not downloaded")
    val state: StateFlow<String> = _state.asStateFlow()

    private var handle = 0L

    val ready get() = model.isFile

    /** Downloads and checks the model. Blocking; call off the main thread. */
    fun download() {
        if (model.isFile) return
        dir.mkdirs()
        // Earlier, smaller models are dropped so only one sits in app storage.
        dir.listFiles()?.filter { it.name.endsWith(".bin") && it != model }?.forEach { it.delete() }
        val part = File(dir, "${choice.file}.part")
        _state.value = "downloading"
        val ok = runCatching {
            // A 2.9 GB download over a phone link drops; a partial file resumes with a Range request.
            val have = if (part.isFile) part.length() else 0L
            val connection = URL(MODEL_BASE + choice.file).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            val resumed = have > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val total = connection.contentLengthLong.let { if (it > 0 && resumed) it + have else it }
            val sha1 = MessageDigest.getInstance("SHA-1")
            if (resumed) {
                part.inputStream().use { old ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = old.read(buffer)
                        if (read < 0) break
                        sha1.update(buffer, 0, read)
                    }
                }
            }
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = if (resumed) have else 0L
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
            val good = sha1.digest().joinToString("") { "%02x".format(it) } == choice.sha1
            if (!good) part.delete()
            good && part.renameTo(model)
        }.getOrDefault(false)
        // A failed transfer keeps its .part so the next tap resumes; a bad hash was deleted above.
        _state.value = if (ok) "ready" else "download failed, tap to resume"
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
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        return nativeTranscribe(handle, samples, threads, HINT)?.trim()
    }

    private external fun nativeInit(path: String): Long

    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, threads: Int, hint: String): String?

    private external fun nativeFree(handle: Long)

    companion object {
        class Model(val name: String, val file: String, val size: String, val sha1: String)

        /** OpenAI Whisper large-v3, whisper.cpp's conversion. SHA-1s from whisper.cpp v1.9.5 models/README.md. */
        val LARGE_V3 = Model("large-v3", "ggml-large-v3.bin", "2.9 GB", "ad82bf6a9043ceed055076d0fd39f5f186ff8062")
        val LARGE_V3_Q5 = Model("large-v3 q5_0", "ggml-large-v3-q5_0.bin", "1.1 GB", "e6e2ed78495d403bef4b7cff42ef4aaadcfea8de")
        const val MODEL_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        /** Primes the decoder with the names the owner talks about, so they are spelled right. */
        const val HINT = "Omarchy, OmaLauncher, OmaWatch, Claude, Codex, ChatGPT, Grok, Hyprland, Arch Linux, pacman, git, Kotlin, Gradle, adb."

        fun floats(pcm16le: ByteArray): FloatArray {
            val shorts = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        }
    }
}
