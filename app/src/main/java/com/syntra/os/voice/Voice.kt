package com.syntra.os.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice

enum class VoiceState { OFF, WAITING_WAKE, LISTENING, SPEAKING }

/** Strips the wake word. Returns null if no wake word, "" if only the wake word was said. */
fun afterWakeWord(text: String, wakeWords: List<String>): String? {
    val lower = text.lowercase()
    val w = wakeWords.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        .mapNotNull { ww -> lower.indexOf(ww).takeIf { it >= 0 }?.let { it to ww } }.minByOrNull { it.first } ?: return null
    return text.substring(w.first + w.second.length).trim(' ', ',', '.', '!', '?')
}

/**
 * JARVIS-style hands-free loop with no popup: listens continuously while the app is open,
 * wakes on a wake word, sends the command, pauses while replying, then listens for a follow-up.
 */
class VoiceLoop(
    private val ctx: Context,
    private val onCommand: (String) -> Unit,
    private val onState: (VoiceState, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var enabled = false
    private var awake = false // true: next utterance is a command (after wake word or reply)
    private var speaking = false
    var wakeWords: List<String> = listOf("syntra", "jarvis")

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val t = tts ?: return@TextToSpeech
            // Best offline voice for the device language: sounds far less robotic than the default.
            t.voices?.filter { it.locale.language == java.util.Locale.getDefault().language && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }?.let { v: Voice -> t.voice = v }
            t.setSpeechRate(1.02f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { main.post { speaking = false; awake = true; listenSoon(250) } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { speaking = false; listenSoon(250) } }
            })
        }
    }

    fun start() {
        if (enabled || !SpeechRecognizer.isRecognitionAvailable(ctx)) return
        enabled = true
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(listener) }
        listenSoon(300)
    }

    fun stop() {
        enabled = false
        main.removeCallbacksAndMessages(null)
        rec?.destroy(); rec = null
        onState(VoiceState.OFF, "")
    }

    /** Push-to-talk: skip the wake word for the next utterance. */
    fun wake() { awake = true; if (!enabled) start() else { rec?.cancel(); listenSoon(50) } }

    fun speak(text: String) {
        speaking = true
        rec?.cancel()
        onState(VoiceState.SPEAKING, "")
        tts?.speak(text.replace(Regex("[*#`_>]"), "").take(800), TextToSpeech.QUEUE_FLUSH, null, "syntra")
    }

    fun shutUp() { tts?.stop(); speaking = false; listenSoon(100) }

    fun release() { stop(); tts?.shutdown() }

    private fun listenSoon(ms: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            if (!enabled || speaking) return@postDelayed
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            onState(if (awake) VoiceState.LISTENING else VoiceState.WAITING_WAKE, "")
            runCatching { rec?.startListening(i) }
        }, ms)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onPartialResults(r: Bundle?) {
            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (awake || afterWakeWord(text, wakeWords) != null) onState(VoiceState.LISTENING, text)
        }
        override fun onResults(r: Bundle?) {
            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val cmd = if (awake) afterWakeWord(text, wakeWords) ?: text else afterWakeWord(text, wakeWords)
            when {
                cmd == null -> listenSoon(100) // background chatter, keep waiting for the wake word
                cmd.isBlank() -> { awake = true; listenSoon(100) } // "Syntra" alone: listen for the command
                else -> { awake = false; onCommand(cmd); listenSoon(200) }
            }
        }
        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stop(); return }
            // Follow-up window closes on silence; go back to wake-word mode.
            if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) awake = false
            listenSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) 800 else 150)
        }
    }
}
