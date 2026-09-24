package me.parham1995.notes.navigation

import android.content.Intent
import me.parham1995.notes.data.TaskDigestWorker
import me.parham1995.notes.widget.RecentNotesWidget

/**
 * Where something outside the app asked to be taken: the digest notification,
 * a launcher shortcut, a widget, an `obsidian://` link.
 */
data class LaunchRequest(
    /** A screen by name: `tasks`, `search`, or `today` for today's daily note. */
    val screen: String? = null,
    /** A note id, from a widget row. */
    val note: Long? = null,
    /** An `obsidian://` link another app handed over, as it was written. */
    val link: String? = null,
)

/**
 * What [intent] asks for, or null when it asks for nothing -- or has already
 * been answered.
 *
 * An activity keeps the intent it was started with for as long as it lives,
 * and hands it back unchanged every time it is created again: on rotation, on
 * a theme or language change, and after the process is killed in the
 * background. Reading it on every creation sent someone back to the task list
 * each time they turned the phone, half an hour after tapping the notification
 * that asked for it. So a creation that is restoring state ([restoring]) has
 * nothing to act on, and nor does a task reopened from recents, which carries
 * the original intent too.
 */
fun launchRequest(
    intent: Intent?,
    restoring: Boolean,
): LaunchRequest? {
    if (intent == null || restoring) return null
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val screen =
        intent.getStringExtra(EXTRA_OPEN)
            ?: SCREEN_TASKS.takeIf { intent.getBooleanExtra(TaskDigestWorker.EXTRA_OPEN_TASKS, false) }
    val note = intent.getLongExtra(RecentNotesWidget.EXTRA_NOTE, 0L).takeIf { it > 0 }
    val link =
        intent.dataString?.takeIf {
            intent.action == Intent.ACTION_VIEW && it.startsWith(OBSIDIAN_SCHEME, ignoreCase = true)
        }
    return if (screen == null && note == null && link == null) null else LaunchRequest(screen, note, link)
}

/**
 * Takes the request off [intent] once it has been read, so that nothing which
 * later reads the same intent -- `getIntent()` survives a recreation -- can act
 * on it a second time.
 */
fun Intent.consumeLaunchRequest() {
    removeExtra(EXTRA_OPEN)
    removeExtra(TaskDigestWorker.EXTRA_OPEN_TASKS)
    removeExtra(RecentNotesWidget.EXTRA_NOTE)
    if (dataString?.startsWith(OBSIDIAN_SCHEME, ignoreCase = true) == true) data = null
}

private const val OBSIDIAN_SCHEME = "obsidian:"

/** Matches `me.parham1995.notes.OPEN` in `res/xml/shortcuts.xml`. */
const val EXTRA_OPEN = "me.parham1995.notes.OPEN"

private const val SCREEN_TASKS = "tasks"

/** Today's daily note, from the launcher shortcut of that name. */
const val SCREEN_TODAY = "today"
