package `in`.medizon.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads the assistant's replies aloud with the phone's text-to-speech (the WebView has no speech of its own).
 * Started on first use; text asked for while it starts up is read once it's ready. [onDone] runs when a reply has
 * been read, or was stopped, so the page can reset its speaker button.
 */
class Speech(context: Context, private val onDone: () -> Unit) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: Pair<String, String>? = null
    private var count = 0

    fun speak(text: String, lang: String) {
        val t = tts
        if (t == null) {
            waiting = text to lang
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) { waiting = null; onDone(); return@TextToSpeech }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "mz$count") onDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { onDone() }
                    override fun onStop(id: String?, interrupted: Boolean) { onDone() }
                })
                waiting?.let { (w, l) -> waiting = null; say(w, l) }
            }
            return
        }
        if (!ready) { waiting = text to lang; return }
        say(text, lang)
    }

    private fun say(text: String, lang: String) {
        val t = tts ?: return
        val locale = if (lang.startsWith("hi")) Locale("hi", "IN") else Locale("en", "IN")
        if (t.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) t.language = locale
        count++
        // Long replies go in pieces: some engines refuse text over their limit
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val parts = if (text.length <= max) listOf(text) else text.chunked(max)
        parts.forEachIndexed { i, p ->
            val id = if (i == parts.lastIndex) "mz$count" else "mz$count-$i"
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
    }

    fun stop() {
        waiting = null
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
