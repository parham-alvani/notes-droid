package me.parham1995.notes.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.padding
import kotlinx.coroutines.flow.first
import me.parham1995.notes.R
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.runCatchingUnlessCancelled

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
class RecentNotesAppWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val entry = context.widgetEntryPoint()
        val colors = entry.widgetColors()
        // As many as the tallest size could show; each size takes what fits it.
        val recent =
            runCatchingUnlessCancelled { entry.vaultRepository().recentlyOpened(MAX_ROWS).first() }
                .getOrDefault(emptyList())
        provideContent {
            GlanceTheme(colors) { RecentNotesContent(recent) }
        }
    }
}

/** The provider the manifest names; see [TasksWidget] for why it keeps this name. */
class RecentNotesWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentNotesAppWidget()

    companion object {
        /** A note id on the launch intent, so a widget row opens that note. */
        const val EXTRA_NOTE = "me.parham1995.notes.NOTE"
    }
}

@Composable
internal fun RecentNotesContent(recent: List<NoteEntity>) {
    val context = LocalContext.current
    val rows = rowsForSize()
    WidgetSurface(GlanceModifier.opens(WidgetIntents.openApp(context))) {
        Headline(
            directed(
                context.getString(if (recent.isEmpty()) R.string.widget_recent_empty else R.string.widget_recent_title),
            ),
            color = GlanceTheme.colors.onSecondaryContainer,
        )
        Stack(recent.take(rows), GlanceModifier.padding(top = HEADLINE_GAP_DP.dp)) { note ->
            MarkedRow(
                marker = RECENT_MARKER,
                markerColor = GlanceTheme.colors.onSurfaceVariant,
                line = directed(note.title.ifBlank { note.name }),
                modifier = GlanceModifier.opens(WidgetIntents.openNote(context, note.id)),
            )
        }
    }
}

private const val RECENT_MARKER = "·"
