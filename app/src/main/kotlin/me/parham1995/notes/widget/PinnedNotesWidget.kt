package me.parham1995.notes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import me.parham1995.notes.R
import me.parham1995.notes.data.PinnedNote
import me.parham1995.notes.data.runCatchingUnlessCancelled

/**
 * Notes pinned from their menu, as cards on the home screen.
 *
 * The widget a phone's home screen is for: a shopping list, a packing list,
 * the note with the door codes in it -- things read far more often than they
 * are changed, and read standing up. Each card is the note's title and its
 * first lines as plain text, and tapping it opens the note.
 *
 * Scoped to the vault being read, like the other two: a pin is a vault and a
 * path, and a note opened from another vault would sit in a reader whose
 * drawer, browser and folder listings all belonged to this one.
 *
 * On Android 12 and up the cards are a scrolling list, or a grid of two
 * columns when the widget is wide enough, built with RemoteCollectionItems --
 * the whole collection handed over in one update, with no RemoteViewsService
 * to bind and keep alive for it. Android 10 and 11 get the column the other
 * widgets draw: as many cards as fit, without scrolling. That is two ways of
 * drawing the same card, but the older one is the layout these widgets
 * already use, and the service it would take to make 10 and 11 scroll is a
 * second process entry point to serve two versions this app's one phone is
 * not running.
 */
class PinnedNotesWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        drawAsync {
            val entry =
                EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            val vaultId = runCatchingUnlessCancelled { entry.vaultRepository().activeVaultId.first() }.getOrDefault(0L)
            val scrolls = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            appWidgetIds.forEach { id ->
                // A column that does not scroll only gets what fits in it.
                val limit = if (scrolls) MAX_PINS else cardsForHeight(heightOf(manager, id))
                val pinned =
                    runCatchingUnlessCancelled { entry.pinnedNotes().resolve(vaultId, limit) }.getOrDefault(emptyList())
                manager.updateAppWidget(id, build(context, pinned, columnsForWidth(widthOf(manager, id))))
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Resizing changes how many columns there are, and on 10 and 11 how
        // many cards fit.
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

    internal fun build(
        context: Context,
        pinned: List<PinnedNote>,
        columns: Int,
        // A parameter only so the column Android 10 and 11 draw can be tested
        // on a runtime that would otherwise always take the collection.
        scrolls: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_pinned)
        views.setTextViewText(R.id.widget_headline, context.getString(R.string.widget_pinned_title))
        views.setOnClickPendingIntent(R.id.widget_headline, openApp(context))
        views.setViewVisibility(R.id.pinned_empty, if (pinned.isEmpty()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.pinned_list, View.GONE)
        views.setViewVisibility(R.id.pinned_grid, View.GONE)
        views.setViewVisibility(R.id.pinned_cards, View.GONE)
        if (pinned.isEmpty()) return views

        if (scrolls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fillCollection(context, views, pinned, if (columns > 1) R.id.pinned_grid else R.id.pinned_list)
        } else {
            views.removeAllViews(R.id.pinned_cards)
            pinned.forEach { note ->
                val card = card(context, note)
                card.setOnClickPendingIntent(R.id.card, openNote(context, note.noteId))
                views.addView(R.id.pinned_cards, card)
            }
            views.setViewVisibility(R.id.pinned_cards, View.VISIBLE)
        }
        return views
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun fillCollection(
        context: Context,
        views: RemoteViews,
        pinned: List<PinnedNote>,
        target: Int,
    ) {
        val items =
            RemoteViews.RemoteCollectionItems
                .Builder()
                .setHasStableIds(true)
                .setViewTypeCount(1)
        pinned.forEach { note ->
            val card = card(context, note)
            // In a collection each item fills in the one template below:
            // a PendingIntent per card is not how a list hands out taps.
            card.setOnClickFillInIntent(R.id.card, Intent().putExtra(RecentNotesWidget.EXTRA_NOTE, note.noteId))
            items.addItem(note.noteId, card)
        }
        views.setRemoteAdapter(target, items.build())
        openTemplate(context)?.let { views.setPendingIntentTemplate(target, it) }
        views.setViewVisibility(target, View.VISIBLE)
    }

    private fun card(
        context: Context,
        note: PinnedNote,
    ): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_pinned_card).apply {
            setTextViewText(R.id.card_title, note.title)
            setTextViewText(R.id.card_body, note.excerpt)
            setViewVisibility(R.id.card_body, if (note.excerpt.isEmpty()) View.GONE else View.VISIBLE)
        }

    private fun launchIntent(context: Context): Intent? =
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The headline: the app, as it was left. */
    private fun openApp(context: Context): PendingIntent? {
        val launch = launchIntent(context) ?: return null
        return PendingIntent.getActivity(
            context,
            HEADLINE_REQUEST,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** One card, on Android 10 and 11, where each has a PendingIntent of its own. */
    private fun openNote(
        context: Context,
        noteId: Long,
    ): PendingIntent? {
        val launch = launchIntent(context)?.putExtra(RecentNotesWidget.EXTRA_NOTE, noteId) ?: return null
        // A request code per note, or every card would open the same one.
        return PendingIntent.getActivity(
            context,
            noteId.toInt(),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * What every card in a collection fills in with its own note.
     *
     * Mutable, because filling it in is a change to it. The intent is explicit
     * -- the launcher activity, by component -- so nothing else can receive it.
     */
    private fun openTemplate(context: Context): PendingIntent? {
        val launch = launchIntent(context) ?: return null
        return PendingIntent.getActivity(
            context,
            TEMPLATE_REQUEST,
            launch,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** Redraws every placed copy. Called after a sync, a write, and a pin. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, PinnedNotesWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, PinnedNotesWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }

        /**
         * The most cards a scrolling widget carries.
         *
         * Every one is a file read inside the broadcast's few seconds and a
         * share of one binder transaction, and a home screen with more than
         * this pinned is a second vault browser rather than a widget.
         */
        const val MAX_PINS = 20

        // Negative, so they cannot meet a note id: PendingIntents with the
        // same request code and an intent the system sees as equal are one
        // PendingIntent, and the last to be made decides where it goes.
        private const val HEADLINE_REQUEST = -1
        private const val TEMPLATE_REQUEST = -2
    }
}

/** Two columns once there is room for two cards side by side. */
internal fun columnsForWidth(widthDp: Int): Int = if (widthDp >= TWO_COLUMNS_DP) 2 else 1

/**
 * How many cards fit in a column that cannot scroll, for Android 10 and 11.
 *
 * An estimate, as [rowsForHeight] is: a card's height depends on how much its
 * note says, which RemoteViews cannot measure. It errs towards fewer, since a
 * card cut off at the bottom edge looks broken and a gap does not.
 */
internal fun cardsForHeight(heightDp: Int): Int {
    if (heightDp <= 0) return 1
    val usable = (heightDp * CARD_USABLE_FRACTION).toInt() - CARD_HEADER_DP
    return (usable / CARD_DP).coerceIn(1, MAX_FIXED_CARDS)
}

/**
 * The width a host is offering a widget, in dp.
 *
 * Portrait uses the minimum width, as [heightOf] uses the minimum height.
 */
internal fun widthOf(
    manager: AppWidgetManager,
    id: Int,
): Int =
    runCatching {
        manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    }.getOrDefault(0)

/**
 * Two cards of about 150dp each, with the gap between them: narrower and
 * eight lines of a note wrap to a word or two apiece.
 */
private const val TWO_COLUMNS_DP = 300

/** A card with a title and a handful of lines, and the gap under it. */
private const val CARD_DP = 110

/** The headline and the container's padding. */
private const val CARD_HEADER_DP = 48

/** The share of the reported height a launcher actually draws; see [rowsForHeight]. */
private const val CARD_USABLE_FRACTION = 0.85f

private const val MAX_FIXED_CARDS = 6
