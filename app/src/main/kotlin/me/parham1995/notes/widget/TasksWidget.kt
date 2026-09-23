package me.parham1995.notes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import me.parham1995.notes.R
import me.parham1995.notes.data.TaskBucket
import me.parham1995.notes.data.TaskBuckets
import me.parham1995.notes.data.TaskDigestWorker
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.feature.capture.CaptureActivity
import java.time.LocalDate

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
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun vaultRepository(): VaultRepository
}

class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        render(context, manager, appWidgetIds)
    }

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        // A provider is not a lifecycle owner and onUpdate is synchronous, so
        // the work runs in the background and the result is pushed when it
        // arrives. The widget keeps its previous content until then rather
        // than blanking.
        drawAsync {
            val repository =
                EntryPointAccessors
                    .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                    .vaultRepository()

            val today = LocalDate.now()
            val open = runCatching { repository.openTasks().first() }.getOrDefault(emptyList())
            val due = open.filter { TaskBuckets.of(it.actionableOn, today) in PRESSING }

            // Per widget: two copies can be different sizes, and each should
            // fill what it was given rather than always drawing four rows.
            ids.forEach { id ->
                val rows = rowsForHeight(heightOf(manager, id))
                manager.updateAppWidget(id, build(context, open.size, due, today, rows))
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Resizing is the moment the row count changes; without this the
        // widget keeps what it drew at its old size until the next sync.
        render(context, manager, intArrayOf(appWidgetId))
    }

    private fun build(
        context: Context,
        openCount: Int,
        due: List<TaskRow>,
        today: LocalDate,
        rows: Int,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tasks)
        val overdue = due.count { TaskBuckets.of(it.actionableOn, today) == TaskBucket.OVERDUE }
        val todayCount = due.size - overdue

        views.setTextViewText(
            R.id.widget_headline,
            when {
                openCount == 0 -> context.getString(R.string.widget_tasks_none)
                due.isEmpty() -> context.getString(R.string.widget_tasks_none_due, openCount)
                overdue == 0 -> context.getString(R.string.widget_tasks_today, todayCount)
                todayCount == 0 -> context.getString(R.string.widget_tasks_overdue, overdue)
                else -> context.getString(R.string.widget_tasks_both, overdue, todayCount)
            },
        )
        views.setTextColor(
            R.id.widget_headline,
            if (overdue > 0) OVERDUE_COLOUR else NORMAL_COLOUR,
        )

        views.removeAllViews(R.id.widget_tasks)
        due.take(rows).forEach { task ->
            val row = RemoteViews(context.packageName, R.layout.widget_task_row)
            val late = TaskBuckets.of(task.actionableOn, today) == TaskBucket.OVERDUE
            row.setTextViewText(R.id.row_marker, if (late) "!" else "-")
            row.setTextColor(R.id.row_marker, if (late) OVERDUE_COLOUR else MUTED_COLOUR)
            row.setTextViewText(R.id.row_text, task.text)
            views.addView(R.id.widget_tasks, row)
        }

        views.setOnClickPendingIntent(R.id.widget_headline, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_tasks, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_capture, capture(context))
        return views
    }

    /** Tapping anywhere opens the app on the task list. */
    private fun openApp(context: Context): PendingIntent? {
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.putExtra(TaskDigestWorker.EXTRA_OPEN_TASKS, true)
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Straight into the capture field, with nothing else in the way. */
    private fun capture(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            CAPTURE_REQUEST,
            Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        /** Redraws every placed widget. Called when a sync changes the tasks. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = android.content.ComponentName(context, TasksWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, TasksWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }

        private val PRESSING = setOf(TaskBucket.OVERDUE, TaskBucket.TODAY)
        private const val CAPTURE_REQUEST = 1
        private const val ROWS = 4

        // naz, by value: RemoteViews cannot read the Compose theme.
        private val OVERDUE_COLOUR = "#FF5070".toColorInt()
        private val NORMAL_COLOUR = "#F5F5F0".toColorInt()
        private val MUTED_COLOUR = "#A8A8A0".toColorInt()
    }
}
