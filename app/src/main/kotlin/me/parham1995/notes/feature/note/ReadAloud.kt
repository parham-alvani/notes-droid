package me.parham1995.notes.feature.note

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import me.parham1995.notes.markdown.SpokenText
import java.util.Locale

/**
 * A note read out by the phone's text-to-speech engine.
 *
 * Each block is queued in its own language, so a note that moves between
 * English and Persian is read by the right voice each time. Many phones ship
 * no Persian voice; then the Persian blocks are left out and [onNoPersian] is
 * told once, rather than an English voice reading Persian letters as noise.
 *
 * The engine starts asynchronously, so a request made before it is ready is
 * held and spoken when it is.
 */
class ReadAloud(
    context: Context,
    private val onSpeaking: (Boolean) -> Unit,
    private val onNoPersian: () -> Unit,
) {
    private var ready = false
    private var pending: List<SpokenText.Utterance>? = null

    private val tts: TextToSpeech =
        TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            val waiting = pending
            pending = null
            if (waiting != null) {
                if (ready) {
                    speak(waiting)
                } else {
                    onSpeaking(false)
                }
            }
        }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == LAST) onSpeaking(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onSpeaking(false)

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = onSpeaking(false)

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) = onSpeaking(false)
            },
        )
    }

    fun speak(utterances: List<SpokenText.Utterance>) {
        onSpeaking(true)
        if (!ready) {
            pending = utterances
            return
        }
        tts.stop()
        val persianVoice = tts.isLanguageAvailable(PERSIAN) >= TextToSpeech.LANG_AVAILABLE
        if (!persianVoice && utterances.any { it.persian }) onNoPersian()
        val said = utterances.filter { !it.persian || persianVoice }
        if (said.isEmpty()) {
            onSpeaking(false)
            return
        }
        said.forEachIndexed { index, utterance ->
            // Captured per request, so each block keeps the voice it was queued with.
            tts.language = if (utterance.persian) PERSIAN else latin()
            tts.speak(utterance.text, TextToSpeech.QUEUE_ADD, null, if (index == said.lastIndex) LAST else "u$index")
        }
    }

    fun stop() {
        pending = null
        tts.stop()
        onSpeaking(false)
    }

    fun shutdown() {
        pending = null
        tts.stop()
        tts.shutdown()
    }

    /** The phone's own language, unless that is Persian -- then English, for the blocks that are not. */
    private fun latin(): Locale = Locale.getDefault().takeUnless { it.language == PERSIAN.language } ?: Locale.US

    private companion object {
        val PERSIAN: Locale = Locale.forLanguageTag("fa-IR")
        const val LAST = "last"
    }
}
