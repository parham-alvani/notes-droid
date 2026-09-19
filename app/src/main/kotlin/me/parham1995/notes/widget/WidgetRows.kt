package me.parham1995.notes.widget

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
    return ((heightDp - HEADER_DP - PADDING_DP) / ROW_DP).coerceIn(minimum, maximum)
}

/** A row of the widget layout: one line of text plus its spacing. */
private const val ROW_DP = 26

/** The headline above the rows. */
private const val HEADER_DP = 24

/** The container's own padding, top and bottom. */
private const val PADDING_DP = 24

private const val MIN_ROWS = 3
private const val MAX_ROWS = 14

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
