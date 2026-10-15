package dev.omalauncher.watch

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.UUID

/**
 * `omawatch-say` from Linux: Android's text to speech on the media stream, so it plays in Bluetooth
 * headphones when they're connected and on the phone's speaker otherwise. Started on first use.
 */
class Speaker(
    context: Context,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    fun say(text: String) {
        main.post {
            if (ready) return@post speak(text)
            pending += text
            if (tts == null) {
                tts = TextToSpeech(app) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) pending.forEach(::speak)
                    pending.clear()
                }
            }
        }
    }

    private fun speak(text: String) {
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, UUID.randomUUID().toString())
    }
}
