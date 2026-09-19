package me.parham1995.notes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.database.NoteEntity

/**
 * The notes you were last reading, one tap away.
 *
 * The other half of what a reader is for. The task widget answers "is there
 * anything I am late on"; this one answers "where was I", which on a phone is
 * the more common question -- reading a vault is picking something up again far
 * more often than it is starting somewhere new.
 *
 * Each row opens its own note rather than the app, which is the entire point.
 */
class RecentNotesWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val repository =
                EntryPointAccessors
                    .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                    .vaultRepository()

            // Per widget, because two copies of the same widget can be
            // different sizes and each should fill what it was given.
            appWidgetIds.forEach { id ->
                val rows = rowsForHeight(heightOf(manager, id))
                val recent =
                    runCatching { repository.recentlyOpened(rows).first() }
                        .getOrDefault(emptyList())
                manager.updateAppWidget(id, build(context, recent))
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Resizing is the moment the row count changes, and without this the
        // widget keeps whatever it drew at its old size until the next sync.
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

    private fun build(
        context: Context,
        recent: List<NoteEntity>,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tasks)
        views.setTextViewText(
            R.id.widget_headline,
            if (recent.isEmpty()) "Nothing read yet" else "Recently read",
        )
        views.setTextColor(R.id.widget_headline, HEADLINE_COLOUR)

        views.removeAllViews(R.id.widget_tasks)
        recent.forEach { note ->
            val row = RemoteViews(context.packageName, R.layout.widget_task_row)
            row.setTextViewText(R.id.row_marker, "·")
            row.setTextColor(R.id.row_marker, MUTED_COLOUR)
            row.setTextViewText(R.id.row_text, note.title.ifBlank { note.name })
            // A distinct request code per note: PendingIntents with the same
            // code and no difference the system can see are the same intent,
            // and every row would open whichever note was added last.
            row.setOnClickPendingIntent(R.id.row_text, openNote(context, note.id))
            views.addView(R.id.widget_tasks, row)
        }

        views.setOnClickPendingIntent(R.id.widget_headline, openNote(context, null))
        return views
    }

    private fun openNote(
        context: Context,
        noteId: Long?,
    ): PendingIntent? {
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        noteId?.let { launch.putExtra(EXTRA_NOTE, it) }
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            noteId?.toInt() ?: 0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** A note id on the launch intent, so a widget row opens that note. */
        const val EXTRA_NOTE = "me.parham1995.notes.NOTE"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RecentNotesWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, RecentNotesWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }

        private const val ROWS = 5
        private val HEADLINE_COLOUR = "#80E5FF".toColorInt()
        private val MUTED_COLOUR = "#A8A8A0".toColorInt()
    }
}
