package me.parham1995.notes.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.absolutePadding
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import me.parham1995.notes.R
import me.parham1995.notes.data.PinnedNotes
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.TaskBucket
import me.parham1995.notes.data.TaskBuckets
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.data.runCatchingUnlessCancelled
import java.time.LocalDate

/**
 * What the widgets read. A widget is composed in a WorkManager session
 * Glance runs, not inside anything Hilt injects into, so they reach the graph
 * through an entry point.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun vaultRepository(): VaultRepository

    fun pinnedNotes(): PinnedNotes

    fun settingsStore(): SettingsStore
}

internal fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

/**
 * What is open, on the home screen.
 *
 * The same question the Tasks screen answers, without opening the app -- which
 * for a read-only reader is most of the value: you are not going to tick
 * anything off here, you want to know whether there is anything to go and look
 * at.
 *
 * A count first and a few lines after it. The vault this was built for has 120
 * overdue tasks, so a widget that tried to list them would be a wall of text
 * that says less than the number does.
 *
 * Composed once per size the host may show it at, so a widget stretched to
 * half the home screen lists more than a small one.
 */
class TasksAppWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val entry = context.widgetEntryPoint()
        val colors = entry.widgetColors()
        val today = LocalDate.now()
        val open =
            runCatchingUnlessCancelled { entry.vaultRepository().openTasks().first() }
                .getOrDefault(emptyList())
        val model = TasksModel(open.size, open.filter { TaskBuckets.of(it.actionableOn, today) in PRESSING }, today)
        provideContent {
            GlanceTheme(colors) { TasksContent(model) }
        }
    }

    private companion object {
        val PRESSING = setOf(TaskBucket.OVERDUE, TaskBucket.TODAY)
    }
}

/**
 * The provider the manifest names. A placed widget is bound to its provider's
 * class name, so the receivers keep the names the RemoteViews providers had
 * and an update does not empty the home screen.
 */
class TasksWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksAppWidget()
}

/** Everything the tasks widget draws from: how many are open, which press, and what day it is. */
internal data class TasksModel(
    val openCount: Int,
    /** Overdue and due today, in the order the task list shows them. */
    val due: List<TaskRow>,
    val today: LocalDate,
)

@Composable
internal fun TasksContent(model: TasksModel) {
    val context = LocalContext.current
    val rows = rowsForSize()
    val overdue = model.due.count { it.isLate(model.today) }
    val todayCount = model.due.size - overdue
    val headline =
        when {
            model.openCount == 0 -> context.getString(R.string.widget_tasks_none)
            model.due.isEmpty() -> context.getString(R.string.widget_tasks_none_due, model.openCount)
            overdue == 0 -> context.getString(R.string.widget_tasks_today, todayCount)
            todayCount == 0 -> context.getString(R.string.widget_tasks_overdue, overdue)
            else -> context.getString(R.string.widget_tasks_both, overdue, todayCount)
        }

    // Tapping anywhere opens the app on the task list; the glyph is the one
    // exception, and sits on top.
    WidgetSurface(GlanceModifier.opens(WidgetIntents.openTasks(context))) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Headline(
                directed(headline),
                GlanceModifier.defaultWeight(),
                color = if (overdue > 0) GlanceTheme.colors.error else GlanceTheme.colors.onBackground,
            )
            // Capture, from the home screen. The one thing worth doing here
            // that reading cannot: a thought arrives away from the desk and
            // the vault is the place it belongs.
            Text(
                text = context.getString(R.string.widget_capture_glyph),
                modifier =
                    GlanceModifier
                        .absolutePadding(left = CAPTURE_PAD_START_DP.dp, right = CAPTURE_PAD_END_DP.dp)
                        .clickable(actionStartActivity(WidgetIntents.capture(context)))
                        .semantics { contentDescription = context.getString(R.string.capture_title) },
                style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = CAPTURE_SP.sp),
            )
        }
        Stack(model.due.take(rows), GlanceModifier.padding(top = HEADLINE_GAP_DP.dp)) { task ->
            val late = task.isLate(model.today)
            MarkedRow(
                marker = if (late) LATE_MARKER else DUE_MARKER,
                markerColor = if (late) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                line = directed(task.text),
            )
        }
    }
}

private fun TaskRow.isLate(today: LocalDate) = TaskBuckets.of(actionableOn, today) == TaskBucket.OVERDUE

private const val LATE_MARKER = "!"
private const val DUE_MARKER = "-"
private const val CAPTURE_SP = 18
private const val CAPTURE_PAD_START_DP = 10
private const val CAPTURE_PAD_END_DP = 2
internal const val HEADLINE_GAP_DP = 6
