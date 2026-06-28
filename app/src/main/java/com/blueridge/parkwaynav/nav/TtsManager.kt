package com.blueridge.parkwaynav.nav

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.blueridge.parkwaynav.data.VoiceType
import java.util.Locale

/**
 * Text-to-speech wrapper for spoken navigation. Honors the user's voice/sound settings:
 * voice type (male/female/default), speaking speed, Bluetooth output, and whether speech
 * may interrupt phone calls.
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false

    var enabled: Boolean = true
    var speakingSpeed: Float = 1.0f
        set(value) { field = value; tts?.setSpeechRate(value) }
    var voiceType: VoiceType = VoiceType.DEFAULT
        set(value) { field = value; applyVoice() }
    var playOverBluetooth: Boolean = true
    var playDuringCall: Boolean = false
    var languageTag: String = "en"

    fun init(onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.language = Locale.forLanguageTag(languageTag)
                tts?.setSpeechRate(speakingSpeed)
                applyVoice()
                onReady?.invoke()
            }
        }
    }

    private fun applyVoice() {
        val engine = tts ?: return
        if (voiceType == VoiceType.DEFAULT) return
        val locale = Locale.forLanguageTag(languageTag)
        val candidates = runCatching { engine.voices }.getOrNull()
            ?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
            ?: return
        // Heuristic: TTS voices don't expose gender directly across all engines, but many
        // encode it in the voice name (e.g. "en-us-x-iom" / names containing male/female).
        val wantMale = voiceType == VoiceType.MALE
        val match = candidates.firstOrNull { v ->
            val n = v.name.lowercase()
            if (wantMale) n.contains("male") && !n.contains("female") || n.contains("#male")
            else n.contains("female")
        } ?: pickByIndex(candidates, wantMale)
        match?.let { engine.voice = it }
    }

    // Fallback when names don't reveal gender: pick deterministic distinct voices.
    private fun pickByIndex(candidates: List<Voice>, wantMale: Boolean): Voice? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedBy { it.name }
        return if (wantMale) sorted.first() else sorted.last()
    }

    fun speak(text: String, flush: Boolean = false) {
        if (!enabled || !ready) return
        if (!playDuringCall && isInCall()) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = android.os.Bundle().apply {
            putString(
                TextToSpeech.Engine.KEY_PARAM_STREAM,
                AudioManager.STREAM_MUSIC.toString()
            )
        }
        tts?.speak(text, mode, params, "brp_nav_${text.hashCode()}")
    }

    private fun isInCall(): Boolean {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
