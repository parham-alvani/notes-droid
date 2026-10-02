package me.parham1995.notes.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import me.parham1995.notes.MainActivity
import me.parham1995.notes.data.TaskDigestWorker
import me.parham1995.notes.feature.capture.CaptureActivity
import java.net.URLEncoder

/**
 * Where a tap on a widget goes.
 *
 * Every intent that carries a target in its extras also names it in a data
 * URI. Two PendingIntents whose intents differ only in extras are one
 * PendingIntent, and whichever row was drawn last decided where every row
 * went; the URI is the difference the system can see. It is also what a test
 * can see, since Glance matches intents the way the system does, by
 * `filterEquals`. Nothing reads the URIs: [me.parham1995.notes.navigation.launchRequest]
 * reads the extras, and only treats a data string as a link when the action
 * is VIEW.
 */
internal object WidgetIntents {
    /** The app, as it was left. */
    fun openApp(context: Context): Intent? = launch(context)

    /** The task list. */
    fun openTasks(context: Context): Intent? =
        launch(context)
            ?.putExtra(TaskDigestWorker.EXTRA_OPEN_TASKS, true)
            ?.setData("daftar://tasks".toUri())

    /** One note by id, inside the vault being read. */
    fun openNote(
        context: Context,
        noteId: Long,
    ): Intent? =
        launch(context)
            ?.putExtra(RecentNotesWidget.EXTRA_NOTE, noteId)
            ?.setData("daftar://note/$noteId".toUri())

    /** Straight into the capture field, with nothing else in the way. */
    fun capture(context: Context): Intent =
        Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * A note the way an `obsidian://` link opens it -- which switches to the
     * note's vault first, where a bare note id would open it inside whichever
     * vault happened to be active.
     */
    fun openLink(
        context: Context,
        vaultName: String,
        path: String,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("obsidian://open?vault=${encode(vaultName)}&file=${encode(path)}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The note picker, for the one widget that asked. */
    fun choose(
        context: Context,
        widgetId: Int,
    ): Intent =
        Intent(context, NoteWidgetConfigureActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            // One task per widget being chosen for, not one shared.
            .setData("daftar-widget://$widgetId".toUri())

    private fun launch(context: Context): Intent? =
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

/** Opens [intent] on a tap, or nothing when there is no intent to open. */
internal fun GlanceModifier.opens(intent: Intent?): GlanceModifier =
    intent?.let { clickable(actionStartActivity(it)) } ?: this
