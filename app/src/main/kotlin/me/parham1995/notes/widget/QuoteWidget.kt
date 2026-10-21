package me.parham1995.notes.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontStyle
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.QuoteNote
import me.parham1995.notes.data.runCatchingUnlessCancelled
import me.parham1995.notes.markdown.Quote
import me.parham1995.notes.markdown.Quotes
import java.time.LocalDate

/**
 * A quote a day, from a note of them.
 *
 * Bound to a note as the note widget is, and chosen with the same picker. It
 * shows one of the note's `[!quote]` callouts: the same one all day, a
 * different one tomorrow ([Quotes.forDay]). The 30-minute update period in
 * its provider info is what turns the day over; no job of its own is needed.
 *
 * A tap on the quote shows the next one, for the rest of today; a tap on the
 * title opens the note.
 *
 * The taps are counted in the widget's Glance state ([QuoteSkips]) and the
 * quote is picked inside the composition, not before it. Glance keeps a
 * session alive for a while after drawing, and an update to a live session
 * recomposes it without calling [provideGlance] again -- so a count read up
 * there moved the quote on the first tap and on none of the ones after it.
 */
class QuoteAppWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val entry = context.widgetEntryPoint()
        val colors = entry.widgetColors()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val pin = QuoteWidgetBindings(context)[widgetId]
        val note =
            pin?.let {
                runCatchingUnlessCancelled { entry.pinnedNotes().quotes(it.vaultId, it.path) }.getOrNull()
            }
        val vault = pin?.let { runCatchingUnlessCancelled { entry.vaultRepository().vault(it.vaultId) }.getOrNull() }
        provideContent {
            val today = LocalDate.now().toEpochDay()
            val skipped = QuoteSkips.read(currentState(), today, pin?.path)
            val quote = note?.let { Quotes.forDay(it.quotes, today, skipped) }
            GlanceTheme(colors) { QuoteContent(QuoteModel(widgetId, pin, note, quote, vault?.label)) }
        }
    }

    override suspend fun onDelete(
        context: Context,
        glanceId: GlanceId,
    ) {
        QuoteWidgetBindings(context).forget(intArrayOf(GlanceAppWidgetManager(context).getAppWidgetId(glanceId)))
    }
}

/** The provider the manifest names. */
class QuoteWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuoteAppWidget()
}

/** A tap on the quote: today's next one, on this widget only. */
class NextQuote : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val path = QuoteWidgetBindings(context)[widgetId]?.path
        updateAppWidgetState(context, glanceId) { QuoteSkips.next(it, LocalDate.now().toEpochDay(), path) }
        QuoteAppWidget().update(context, glanceId)
    }
}

/** What one placed quote widget draws from. */
internal data class QuoteModel(
    val widgetId: Int,
    /** What the widget is bound to, or null when it has yet to be given a note. */
    val pin: Pin?,
    /** The note the pin names, or null when it is not in the vault. */
    val note: QuoteNote?,
    /** Today's quote, or null when the note holds none. */
    val quote: Quote?,
    /** The vault's name, for the link that opens the note in it. */
    val vaultName: String?,
)

@Composable
internal fun QuoteContent(model: QuoteModel) {
    val context = LocalContext.current
    val note = model.note
    val vaultName = model.vaultName
    // Unbound, gone, or a note with no quote in it: say which, and let a tap
    // choose again.
    if (note == null || vaultName == null || model.quote == null) {
        val why =
            when {
                model.pin == null -> R.string.widget_quote_choose
                note == null || vaultName == null -> R.string.widget_note_gone
                else -> R.string.widget_quote_none
            }
        WidgetSurface(GlanceModifier.opens(WidgetIntents.choose(context, model.widgetId))) {
            Headline(directed(note?.title ?: context.getString(R.string.widget_quote_title)))
            Text(
                text = context.getString(why),
                modifier = GlanceModifier.fillMaxWidth().padding(top = HEADLINE_GAP_DP.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = ROW_SP.sp),
            )
        }
        return
    }

    val next = GlanceModifier.clickable(actionRunCallback<NextQuote>())
    WidgetSurface(next) {
        Headline(
            directed(note.title),
            GlanceModifier.fillMaxWidth().opens(WidgetIntents.openLink(context, vaultName, note.path)),
            color = GlanceTheme.colors.onSurfaceVariant,
        )
        val lines = model.quote.lines
        val source = model.quote.source
        LazyColumn(GlanceModifier.fillMaxSize().padding(top = HEADLINE_GAP_DP.dp)) {
            items(lines.size, { it.toLong() }) { index ->
                DirectedLine(
                    directed(lines[index]),
                    GlanceModifier.fillMaxWidth().padding(bottom = QUOTE_GAP_DP.dp).then(next),
                    TextStyle(color = GlanceTheme.colors.onSurface, fontSize = QUOTE_SP.sp),
                )
            }
            if (source != null) {
                item(SOURCE_ITEM) {
                    DirectedLine(
                        directed("— $source"),
                        GlanceModifier.fillMaxWidth().then(next),
                        TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = ROW_SP.sp,
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                }
            }
        }
    }
}

/** Larger than a note's lines: a quote is the whole of what the widget says. */
private const val QUOTE_SP = 16
private const val QUOTE_GAP_DP = 8
private const val SOURCE_ITEM = -1L

/**
 * How many times a quote widget has been tapped on to the next quote, kept in
 * its Glance state. The count is for one day and one note: a new day, or the
 * widget pointed at another note, starts again at that day's quote.
 */
internal object QuoteSkips {
    private val DAY = longPreferencesKey("quote_day")
    private val PATH = stringPreferencesKey("quote_path")
    private val COUNT = intPreferencesKey("quote_skips")

    fun read(
        state: Preferences,
        epochDay: Long,
        path: String?,
    ): Int = if (state[DAY] == epochDay && state[PATH] == path) state[COUNT] ?: 0 else 0

    fun next(
        state: MutablePreferences,
        epochDay: Long,
        path: String?,
    ) {
        val count = read(state, epochDay, path) + 1
        state[DAY] = epochDay
        state[COUNT] = count
        if (path == null) state.remove(PATH) else state[PATH] = path
    }
}

/**
 * Which note each placed quote widget draws from: the note widget's bindings,
 * in a file of their own so the two kinds never read each other's.
 */
class QuoteWidgetBindings(
    context: Context,
) {
    private val notes = NoteWidgetBindings(context, "quote_widgets")

    operator fun get(widgetId: Int): Pin? = notes[widgetId]

    operator fun set(
        widgetId: Int,
        pin: Pin,
    ) {
        notes[widgetId] = pin
    }

    fun forget(widgetIds: IntArray) = notes.forget(widgetIds)
}
