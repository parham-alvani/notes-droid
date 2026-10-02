package me.parham1995.notes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.PinnedNote
import me.parham1995.notes.data.runCatchingUnlessCancelled

/**
 * One note, whole, on the home screen -- a sticky note.
 *
 * The pinned notes widget shows several notes a few lines each; this is for
 * the one note that should be readable without opening anything: an address,
 * the door codes, what to bring. Each placed copy is bound to a vault and a
 * path of its own ([NoteWidgetBindings]), so two can show two notes, and one
 * can show a note from a vault other than the one being read.
 *
 * Placed from the note's menu, it arrives bound. Placed from the launcher's
 * widget list, it arrives empty and asks for a note when tapped.
 *
 * The text is a scrolling list of lines, each reading its own way.
 */
class NoteAppWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val entry = context.widgetEntryPoint()
        val colors = entry.widgetColors()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val pin = NoteWidgetBindings(context)[widgetId]
        val note =
            pin?.let {
                runCatchingUnlessCancelled {
                    entry.pinnedNotes().whole(
                        it.vaultId,
                        it.path,
                    )
                }.getOrNull()
            }
        val vault = pin?.let { runCatchingUnlessCancelled { entry.vaultRepository().vault(it.vaultId) }.getOrNull() }
        val model = NoteModel(widgetId, pin, note, vault?.label)
        provideContent {
            GlanceTheme(colors) { NoteContent(model) }
        }
    }

    override suspend fun onDelete(
        context: Context,
        glanceId: GlanceId,
    ) {
        NoteWidgetBindings(context).forget(intArrayOf(GlanceAppWidgetManager(context).getAppWidgetId(glanceId)))
    }
}

/**
 * The provider the manifest names (see [TasksWidget] for why it keeps this
 * name), and the one that hears the launcher's answer to "put this note on
 * the home screen": the id it gave the new widget, and the note the request
 * was made for.
 */
class NoteWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteAppWidget()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION_BIND) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val vaultId = intent.getLongExtra(EXTRA_VAULT, 0L)
            val path = intent.getStringExtra(EXTRA_PATH)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && vaultId > 0 && path != null) {
                NoteWidgetBindings(context)[id] = Pin(vaultId, path)
                // Through Glance's own update path, which holds the broadcast
                // open for as long as it needs and records the receiver.
                onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(id))
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_BIND = "me.parham1995.notes.widget.NOTE_WIDGET_BIND"
        private const val EXTRA_VAULT = "me.parham1995.notes.widget.VAULT"
        private const val EXTRA_PATH = "me.parham1995.notes.widget.PATH"

        /**
         * Asks the launcher to place a widget showing [pin]. False when the
         * launcher cannot be asked -- then the widget list is the way.
         */
        fun request(
            context: Context,
            pin: Pin,
        ): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            if (!manager.isRequestPinAppWidgetSupported) return false
            val callback =
                PendingIntent.getBroadcast(
                    context,
                    (pin.vaultId.toString() + pin.path).hashCode(),
                    Intent(context, NoteWidget::class.java)
                        .setAction(ACTION_BIND)
                        .putExtra(EXTRA_VAULT, pin.vaultId)
                        .putExtra(EXTRA_PATH, pin.path),
                    // Mutable: the launcher adds the new widget's id to it.
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            return manager.requestPinAppWidget(ComponentName(context, NoteWidget::class.java), null, callback)
        }
    }
}

/** What one placed note widget draws from. */
internal data class NoteModel(
    val widgetId: Int,
    /** What the widget is bound to, or null when it has yet to be given a note. */
    val pin: Pin?,
    /** The note the pin names, or null when it is not in the vault. */
    val note: PinnedNote?,
    /** The vault's name, for the link that opens the note in it. */
    val vaultName: String?,
)

@Composable
internal fun NoteContent(model: NoteModel) {
    val context = LocalContext.current
    val note = model.note
    val vaultName = model.vaultName
    // Unbound, or bound to a note that has since gone: say so, and let a tap
    // choose again rather than drawing a blank card forever.
    if (note == null || vaultName == null) {
        WidgetSurface(GlanceModifier.opens(WidgetIntents.choose(context, model.widgetId))) {
            Headline(directed(context.getString(R.string.widget_note_title)))
            Text(
                text =
                    context.getString(
                        if (model.pin ==
                            null
                        ) {
                            R.string.widget_note_choose
                        } else {
                            R.string.widget_note_gone
                        },
                    ),
                modifier = GlanceModifier.fillMaxWidth().padding(top = HEADLINE_GAP_DP.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = ROW_SP.sp),
            )
        }
        return
    }

    val open = WidgetIntents.openLink(context, vaultName, note.path)
    WidgetSurface(GlanceModifier.opens(open)) {
        Headline(directed(note.title))
        val lines = note.excerpt.lines().filter { it.isNotBlank() }
        LazyColumn(GlanceModifier.fillMaxSize().padding(top = HEADLINE_GAP_DP.dp)) {
            // Every line opens the same note.
            items(lines.size, { it.toLong() }) { index ->
                DirectedLine(
                    directed(lines[index]),
                    GlanceModifier.fillMaxWidth().padding(bottom = LINE_GAP_DP.dp).opens(open),
                    TextStyle(color = GlanceTheme.colors.onSurface, fontSize = ROW_SP.sp),
                )
            }
        }
    }
}

private const val LINE_GAP_DP = 6

/**
 * Which note each placed note widget shows, by widget id.
 *
 * A vault and a path, as a pin is: ids are reissued by a reindex, and a
 * widget should survive one.
 */
class NoteWidgetBindings(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("note_widgets", Context.MODE_PRIVATE)

    operator fun get(widgetId: Int): Pin? {
        val stored = preferences.getString(key(widgetId), null) ?: return null
        val vaultId = stored.substringBefore(':').toLongOrNull() ?: return null
        return Pin(vaultId, stored.substringAfter(':'))
    }

    operator fun set(
        widgetId: Int,
        pin: Pin,
    ) = preferences.edit { putString(key(widgetId), "${pin.vaultId}:${pin.path}") }

    fun forget(widgetIds: IntArray) = preferences.edit { widgetIds.forEach { remove(key(it)) } }

    private fun key(widgetId: Int) = "widget_$widgetId"
}
