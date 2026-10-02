package me.parham1995.notes.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Every placed widget, redrawn: after a sync, a write, or a change of pins. */
object Widgets {
    /**
     * Redraws every placed copy of every widget. Cheap when none is placed,
     * and quick when some are: Glance only enqueues the sessions here and
     * composes them in a worker of its own.
     */
    suspend fun refreshAll(context: Context) {
        TasksAppWidget().updateAll(context)
        RecentNotesAppWidget().updateAll(context)
        PinnedNotesAppWidget().updateAll(context)
        NoteAppWidget().updateAll(context)
    }

    /** The pinned notes widget alone, for a pin added or removed. */
    suspend fun refreshPinned(context: Context) = PinnedNotesAppWidget().updateAll(context)
}
