package me.parham1995.notes.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
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
 * The cards are a scrolling list, or a grid of two columns when the widget is
 * wide enough. Glance hands the collection over in one update on Android 12
 * and up and serves it from its own RemoteViewsService before that, so every
 * version scrolls. A grid needs Android 12; before it the two columns fall
 * back to the list.
 */
class PinnedNotesAppWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val entry = context.widgetEntryPoint()
        val colors = entry.widgetColors()
        val vaultId = runCatchingUnlessCancelled { entry.vaultRepository().activeVaultId.first() }.getOrDefault(0L)
        val pinned =
            runCatchingUnlessCancelled { entry.pinnedNotes().resolve(vaultId, MAX_PINS) }.getOrDefault(emptyList())
        provideContent {
            GlanceTheme(colors) { PinnedNotesContent(pinned) }
        }
    }

    companion object {
        /**
         * The most cards a widget carries.
         *
         * Every one is a file read inside the session's few seconds and a
         * share of one binder transaction, and a home screen with more than
         * this pinned is a second vault browser rather than a widget.
         */
        const val MAX_PINS = 20
    }
}

/** The provider the manifest names; see [TasksWidget] for why it keeps this name. */
class PinnedNotesWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PinnedNotesAppWidget()
}

@Composable
internal fun PinnedNotesContent(
    pinned: List<PinnedNote>,
    columns: Int = columnsForSize(),
) {
    val context = LocalContext.current
    WidgetSurface {
        Headline(
            directed(context.getString(R.string.widget_pinned_title)),
            GlanceModifier.opens(WidgetIntents.openApp(context)),
        )
        val body = GlanceModifier.fillMaxSize().padding(top = HEADLINE_GAP_DP.dp)
        when {
            pinned.isEmpty() ->
                Text(
                    text = context.getString(R.string.widget_pinned_empty),
                    modifier = body,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = ROW_SP.sp),
                )
            columns > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                LazyVerticalGrid(GridCells.Fixed(columns), body.semantics { testTag = PINNED_GRID }) {
                    items(pinned, { it.noteId }) { PinnedCard(it, gutter = true) }
                }
            else ->
                LazyColumn(body.semantics { testTag = PINNED_LIST }) {
                    items(pinned, { it.noteId }) { PinnedCard(it, gutter = false) }
                }
        }
    }
}

/**
 * One pinned note: its title and its first lines, each line reading its own
 * way. The gap under a card is padding on a box around it, since a list has no
 * spacing of its own and a view's background covers its own padding.
 */
@Composable
private fun PinnedCard(
    note: PinnedNote,
    gutter: Boolean,
) {
    val context = LocalContext.current
    val around =
        if (gutter) {
            GlanceModifier.padding(start = CARD_GUTTER_DP.dp, end = CARD_GUTTER_DP.dp, bottom = CARD_GAP_DP.dp)
        } else {
            GlanceModifier.padding(bottom = CARD_GAP_DP.dp)
        }
    Box(modifier = around.fillMaxWidth()) {
        Column(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    // A shade lifted off the widget's own background: naz's
                    // DarkGrey in the dark scheme, the panel colour in the
                    // light one. Glance's palette has no surfaceContainer.
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(CARD_CORNER_DP.dp)
                    .padding(CARD_PADDING_DP.dp)
                    .opens(WidgetIntents.openNote(context, note.noteId)),
        ) {
            DirectedLine(
                directed(note.title),
                GlanceModifier.fillMaxWidth(),
                TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = CARD_TITLE_SP.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            // A line each, so a Persian line sits at the right beside an
            // English one at the left. The excerpt is at most eight lines,
            // which with the title keeps the card inside a container's ten.
            note.excerpt
                .lines()
                .filter { it.isNotBlank() }
                .take(MAX_CHILDREN - 1)
                .forEach { line ->
                    DirectedLine(
                        directed(line),
                        GlanceModifier.fillMaxWidth().padding(top = CARD_LINE_GAP_DP.dp),
                        TextStyle(color = GlanceTheme.colors.onSurface, fontSize = CARD_BODY_SP.sp),
                        maxLines = CARD_LINE_MAX_LINES,
                    )
                }
        }
    }
}

/** Two columns once there is room for two cards side by side. */
internal fun columnsForWidth(widthDp: Int): Int = if (widthDp >= TWO_COLUMNS_DP) 2 else 1

/** [columnsForWidth] for the size being composed for. */
@Composable
private fun columnsForSize(): Int =
    columnsForWidth(
        LocalSize.current.width.value
            .toInt(),
    )

/**
 * Two cards of about 150dp each, with the gap between them: narrower and
 * eight lines of a note wrap to a word or two apiece.
 */
private const val TWO_COLUMNS_DP = 300

internal const val PINNED_LIST = "pinned_list"
internal const val PINNED_GRID = "pinned_grid"

private const val CARD_GAP_DP = 6
private const val CARD_GUTTER_DP = 3
private const val CARD_CORNER_DP = 12
private const val CARD_PADDING_DP = 10
private const val CARD_LINE_GAP_DP = 4
private const val CARD_TITLE_SP = 14
private const val CARD_BODY_SP = 12

/** A line of a card that wraps is shown to here and clipped, as the card was. */
private const val CARD_LINE_MAX_LINES = 2
