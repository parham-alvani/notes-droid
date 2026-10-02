package me.parham1995.notes.widget

import me.parham1995.notes.markdown.MdDirection
import me.parham1995.notes.markdown.TextDirection

/**
 * How many rows fit in a widget of a given height.
 *
 * Both widgets drew a fixed four or five rows whatever size they were given,
 * so a widget resized to half a home screen showed the same four lines as a
 * small one and left the rest empty. The height is the one Glance composes
 * for ([androidx.glance.LocalSize]), which follows the host's resizing.
 *
 * Deliberately an estimate. A widget cannot measure itself, so this is
 * arithmetic on a row height that matches the layout; being one row out costs
 * a little space or a clipped last row, and both are better than four rows in
 * a box built for twelve.
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
 * The number a host reports is the cell the widget sits in, not the space
 * inside it: measured on the device, a widget told 344dp had a panel of about
 * 280dp, the rest being the launcher's own margins. The proportion is the
 * launcher's business and differs between them, so this errs low on purpose
 * -- a row too few leaves a gap nobody notices, and a row too many is drawn
 * half off the bottom edge, which is what the previous two attempts at this
 * number did.
 */
private const val USABLE_FRACTION = 0.85f

private const val MIN_ROWS = 3

/**
 * The most rows any size is given, and so the most a widget asks the
 * database for: every row is a query result and a PendingIntent.
 */
internal const val MAX_ROWS = 18

/**
 * A line of text and the direction it reads in.
 *
 * Glance lays every text view out in the locale's direction, with no way to
 * ask for the first strong character instead -- so a Persian line on an
 * English phone would start at the left and put its full stop on the wrong
 * side, and an English line on a Persian phone the reverse. The text here is
 * wrapped in a Unicode isolate that fixes its own direction whatever the
 * paragraph's is, and [rtl] says which edge to align it to and, in a row with
 * a marker, which side the marker goes.
 */
internal data class DirectedText(
    val text: String,
    val rtl: Boolean,
)

/** [text] the way it reads: the same rule the renderer applies per block. */
internal fun directed(text: String): DirectedText {
    val rtl = TextDirection.of(text) == MdDirection.RTL
    return DirectedText((if (rtl) RLI else LRI) + text + PDI, rtl)
}

/** RIGHT-TO-LEFT ISOLATE, LEFT-TO-RIGHT ISOLATE, POP DIRECTIONAL ISOLATE. */
private const val RLI = '⁧'
private const val LRI = '⁦'
private const val PDI = '⁩'
