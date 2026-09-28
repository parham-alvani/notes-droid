package me.parham1995.notes.feature.note

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import me.parham1995.notes.R
import me.parham1995.notes.markdown.SpokenText
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * A note read out by the phone's text-to-speech engine.
 *
 * Each block is queued in its own language, so a note that moves between
 * English and Persian is read by the right voice each time. Many phones ship
 * no Persian voice; then the Persian blocks are left out and [onNoPersian] is
 * told once, rather than an English voice reading Persian letters as noise.
 *
 * [onBlock] is told which of the note's blocks is being read, so the page can
 * follow along, and null when reading stops. While it reads, a notification
 * says so and carries a Stop button: reading goes on with the screen off or
 * another app in front, and has to be stoppable from there.
 *
 * The engine starts asynchronously, so a request made before it is ready is
 * held and spoken when it is. Its callbacks arrive on a binder thread and are
 * handed to the main one before anything is told.
 */
class ReadAloud(
    context: Context,
    private val title: () -> String,
    private val onBlock: (Int?) -> Unit,
    private val onNoPersian: () -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: List<SpokenText.Utterance>? = null
    private var queued: List<SpokenText.Utterance> = emptyList()

    // Each reading's utterance ids carry its generation. Stopping one reading
    // to start the next reports the old one stopped, late and from another
    // thread; without this that report cleared the new reading's highlight.
    @Volatile private var generation = 0

    /** The utterance's position in [queued], or null when it is from an older reading. */
    private fun current(utteranceId: String?): Int? {
        val (gen, index) = utteranceId?.removePrefix(PREFIX)?.split('-')?.takeIf { it.size == 2 } ?: return null
        return index.toIntOrNull()?.takeIf { gen.toIntOrNull() == generation }
    }

    private val tts: TextToSpeech =
        TextToSpeech(this.context) { status ->
            ready = status == TextToSpeech.SUCCESS
            val waiting = pending
            pending = null
            if (waiting != null) {
                if (ready) {
                    speak(waiting)
                } else {
                    finished()
                }
            }
        }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val index = current(utteranceId) ?: return
                    queued.getOrNull(index)?.let { utterance -> main.post { onBlock(utterance.block) } }
                }

                override fun onDone(utteranceId: String?) {
                    if (current(utteranceId) == queued.lastIndex) main.post { finished() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (current(utteranceId) != null) main.post { finished() }
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) {
                    if (current(utteranceId) != null) main.post { finished() }
                }

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) {
                    if (current(utteranceId) != null) main.post { finished() }
                }
            },
        )
    }

    fun speak(utterances: List<SpokenText.Utterance>) {
        active = WeakReference(this)
        if (!ready) {
            pending = utterances
            onBlock(utterances.firstOrNull()?.block)
            return
        }
        generation++
        tts.stop()
        val persianVoice = tts.isLanguageAvailable(PERSIAN) >= TextToSpeech.LANG_AVAILABLE
        if (!persianVoice && utterances.any { it.persian }) onNoPersian()
        val said = utterances.filter { !it.persian || persianVoice }
        if (said.isEmpty()) {
            finished()
            return
        }
        queued = said
        said.forEachIndexed { index, utterance ->
            // Captured per request, so each block keeps the voice it was queued with.
            tts.language = if (utterance.persian) PERSIAN else latin()
            tts.speak(utterance.text, TextToSpeech.QUEUE_ADD, null, "$PREFIX$generation-$index")
        }
        onBlock(said.first().block)
        notifyReading()
    }

    fun stop() {
        pending = null
        generation++
        tts.stop()
        finished()
    }

    fun shutdown() {
        pending = null
        tts.stop()
        tts.shutdown()
        finished()
    }

    private fun finished() {
        onBlock(null)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        if (active?.get() === this) active = null
    }

    private fun notifyReading() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.read_aloud_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stop =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, StopReading::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val open =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(
                    context,
                    0,
                    it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_read_aloud)
                .setContentTitle(context.getString(R.string.read_aloud_now))
                .setContentText(title())
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.note_stop_reading), stop)
                .build(),
        )
    }

    /** The phone's own language, unless that is Persian -- then English, for the blocks that are not. */
    private fun latin(): Locale = Locale.getDefault().takeUnless { it.language == PERSIAN.language } ?: Locale.US

    /** The notification's Stop button: whatever is reading, stop it. */
    class StopReading : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            active?.get()?.stop()
        }
    }

    companion object {
        private val PERSIAN: Locale = Locale.forLanguageTag("fa-IR")
        private const val PREFIX = "u"
        private const val CHANNEL_ID = "read-aloud"
        private const val NOTIFICATION_ID = 3

        /**
         * The one reading now, for the notification's button to reach. Weak:
         * the note screen owns the reader and shuts it down when it goes.
         */
        @Volatile private var active: WeakReference<ReadAloud>? = null
    }
}
