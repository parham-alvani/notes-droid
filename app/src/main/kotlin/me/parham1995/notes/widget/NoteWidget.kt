package me.parham1995.notes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.net.toUri
import dagger.hilt.android.EntryPointAccessors
import me.parham1995.notes.MainActivity
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.PinnedNote
import me.parham1995.notes.data.runCatchingUnlessCancelled
import java.net.URLEncoder

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
 * Android 12 and up scroll the text a line at a time in a collection; 10 and
 * 11 get as much as one text view holds, as the pinned widget does.
 */
class NoteWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        drawAsync {
            val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            val bindings = NoteWidgetBindings(context)
            appWidgetIds.forEach { id ->
                val pin = bindings[id]
                val note =
                    pin?.let {
                        runCatchingUnlessCancelled {
                            entry.pinnedNotes().whole(
                                it.vaultId,
                                it.path,
                            )
                        }.getOrNull()
                    }
                val vault =
                    pin?.let {
                        runCatchingUnlessCancelled {
                            entry.vaultRepository().vault(
                                it.vaultId,
                            )
                        }.getOrNull()
                    }
                manager.updateAppWidget(id, build(context, id, pin, note, vault?.label))
            }
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // The launcher's answer to "put this note on the home screen": the id
        // it gave the new widget, and the note the request was made for.
        if (intent.action == ACTION_BIND) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val vaultId = intent.getLongExtra(EXTRA_VAULT, 0L)
            val path = intent.getStringExtra(EXTRA_PATH)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && vaultId > 0 && path != null) {
                NoteWidgetBindings(context)[id] = Pin(vaultId, path)
                onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(id))
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        NoteWidgetBindings(context).forget(appWidgetIds)
    }

    internal fun build(
        context: Context,
        widgetId: Int,
        pin: Pin?,
        note: PinnedNote?,
        vaultName: String?,
        scrolls: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_note)
        views.setViewVisibility(R.id.note_lines, View.GONE)
        views.setViewVisibility(R.id.note_text, View.GONE)
        views.setViewVisibility(R.id.note_empty, View.GONE)

        // Unbound, or bound to a note that has since gone: say so, and let a
        // tap choose again rather than drawing a blank card forever.
        if (note == null || vaultName == null) {
            views.setTextViewText(R.id.widget_headline, context.getString(R.string.widget_note_title))
            views.setTextViewText(
                R.id.note_empty,
                context.getString(if (pin == null) R.string.widget_note_choose else R.string.widget_note_gone),
            )
            views.setViewVisibility(R.id.note_empty, View.VISIBLE)
            val choose = choose(context, widgetId)
            views.setOnClickPendingIntent(R.id.widget_note_root, choose)
            views.setOnClickPendingIntent(R.id.note_empty, choose)
            views.setOnClickPendingIntent(R.id.widget_headline, choose)
            return views
        }

        views.setTextViewText(R.id.widget_headline, note.title)
        val open = open(context, widgetId, vaultName, note.path)
        views.setOnClickPendingIntent(R.id.widget_headline, open)
        views.setOnClickPendingIntent(R.id.widget_note_root, open)
        val lines = note.excerpt.lines().filter { it.isNotBlank() }
        if (scrolls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fillLines(context, views, lines, open)
        } else {
            views.setTextViewText(R.id.note_text, lines.joinToString("\n"))
            views.setOnClickPendingIntent(R.id.note_text, open)
            views.setViewVisibility(R.id.note_text, View.VISIBLE)
        }
        return views
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun fillLines(
        context: Context,
        views: RemoteViews,
        lines: List<String>,
        open: PendingIntent,
    ) {
        val items =
            RemoteViews.RemoteCollectionItems
                .Builder()
                .setViewTypeCount(1)
        lines.forEachIndexed { index, line ->
            val row = RemoteViews(context.packageName, R.layout.widget_note_line)
            row.setTextViewText(R.id.line, line)
            // Every line opens the same note; the template says which.
            row.setOnClickFillInIntent(R.id.line, Intent())
            items.addItem(index.toLong(), row)
        }
        views.setRemoteAdapter(R.id.note_lines, items.build())
        views.setPendingIntentTemplate(R.id.note_lines, open)
        views.setViewVisibility(R.id.note_lines, View.VISIBLE)
    }

    companion object {
        const val ACTION_BIND = "me.parham1995.notes.widget.NOTE_WIDGET_BIND"
        private const val EXTRA_VAULT = "me.parham1995.notes.widget.VAULT"
        private const val EXTRA_PATH = "me.parham1995.notes.widget.PATH"

        /**
         * The note, opened the way an `obsidian://` link opens it -- which
         * switches to the note's vault first, where a bare note id would open
         * it inside whichever vault happened to be active.
         */
        private fun open(
            context: Context,
            widgetId: Int,
            vaultName: String,
            path: String,
        ): PendingIntent {
            val uri = "obsidian://open?vault=${encode(vaultName)}&file=${encode(path)}"
            val intent =
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(uri.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Mutable: a line of the collection fills this template in.
            return PendingIntent.getActivity(
                context,
                widgetId,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun choose(
            context: Context,
            widgetId: Int,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, NoteWidgetConfigureActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    // One task per widget being chosen for, not one shared.
                    .setData("daftar-widget://$widgetId".toUri()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

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

        /** Redraws every placed copy: after a sync, a write, or a new binding. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, NoteWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, NoteWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}

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
