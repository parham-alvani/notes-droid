package me.parham1995.notes.widget

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs [draw] off the main thread, keeping the broadcast alive until it ends.
 *
 * A widget provider is a broadcast receiver, and once `onReceive` returns the
 * system is free to kill a process with nothing else running -- which, for a
 * widget updated by a sync in the background, is the usual case. A coroutine
 * merely launched from `onUpdate` could die half way through reading the
 * database, and the widget kept whatever it last drew. `goAsync` holds the
 * broadcast open until `finish`, which is what makes the work count.
 */
internal fun BroadcastReceiver.drawAsync(draw: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            draw()
        } finally {
            pending.finish()
        }
    }
}

/**
 * How many rows fit in a widget of a given height.
 *
 * Both widgets drew a fixed four or five rows whatever size they were given,
 * so a widget resized to half a home screen showed the same four lines as a
 * small one and left the rest empty. The height the host is offering is in the
 * options bundle, and it changes when the widget is resized.
 *
 * Deliberately an estimate. RemoteViews cannot measure, so this is arithmetic
 * on a row height that matches the layout; being one row out costs a little
 * space or a clipped last row, and both are better than four rows in a box
 * built for twelve.
 */
internal fun rowsForHeight(
    heightDp: Int,
    minimum: Int = MIN_ROWS,
    maximum: Int = MAX_ROWS,
): Int {
    // Zero is what the host offers before it has decided, which is not a
    // reason to draw nothing.
    if (heightDp <= 0) return minimum
    val drawable = (heightDp * USABLE_FRACTION).toInt()
    return ((drawable - HEADER_DP - PADDING_DP) / ROW_DP).coerceIn(minimum, maximum)
}

/**
 * A row of the widget layout: one line of text plus its spacing.
 *
 * Measured off a screenshot of the real widget at 17dp, and rounded up so a
 * misjudged row costs a gap rather than a clipped last line.
 */
private const val ROW_DP = 18

/** The headline above the rows. */
private const val HEADER_DP = 24

/** The container's own padding, top and bottom. */
private const val PADDING_DP = 24

/**
 * How much of the reported height the widget actually gets to draw in.
 *
 * The number in the options bundle is the cell the widget sits in, not the
 * space inside it: measured on the device, a widget told 344dp had a panel of
 * about 280dp, the rest being the launcher's own margins. The proportion is
 * the launcher's business and differs between them, so this errs low on
 * purpose -- a row too few leaves a gap nobody notices, and a row too many is
 * drawn half off the bottom edge, which is what the previous two attempts at
 * this number did.
 */
private const val USABLE_FRACTION = 0.85f

private const val MIN_ROWS = 3
private const val MAX_ROWS = 18

/**
 * The height a host is offering a widget, in dp.
 *
 * Portrait uses the minimum height; that is the number the home screen fixes
 * when the widget is placed or resized.
 */
internal fun heightOf(
    manager: android.appwidget.AppWidgetManager,
    id: Int,
): Int =
    runCatching {
        manager.getAppWidgetOptions(id).getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
    }.getOrDefault(0)
