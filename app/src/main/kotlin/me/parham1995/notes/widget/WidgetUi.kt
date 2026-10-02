package me.parham1995.notes.widget

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.absolutePadding
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * The card every widget is drawn on: the theme's background, the widget's
 * padding, and the system's corner radius on Android 12 and up, where the
 * launcher rounds every widget to the same curve.
 */
@Composable
internal fun WidgetSurface(
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var surface =
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.background)
    surface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.cornerRadius(android.R.dimen.system_app_widget_background_radius)
        } else {
            surface.cornerRadius(WIDGET_CORNER_DP.dp)
        }
    Column(modifier = surface.padding(WIDGET_PADDING_DP.dp).then(modifier), content = content)
}

/** How many rows the size being composed for holds; see [rowsForHeight]. */
@Composable
internal fun rowsForSize(): Int =
    rowsForHeight(
        LocalSize.current.height.value
            .toInt(),
    )

/** The line at the top of a widget: one line, bold, and clipped rather than wrapped. */
@Composable
internal fun Headline(
    text: DirectedText,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider = GlanceTheme.colors.onBackground,
) {
    DirectedLine(
        text,
        modifier,
        TextStyle(color = color, fontSize = HEADLINE_SP.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

/**
 * One line of a note, aligned to the edge it reads from.
 *
 * Left and right rather than start and end: the text's direction is its own
 * ([directed]), not the locale's, and start and end follow the locale.
 */
@Composable
internal fun DirectedLine(
    line: DirectedText,
    modifier: GlanceModifier = GlanceModifier,
    style: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = line.text,
        modifier = modifier,
        style = style.copy(textAlign = if (line.rtl) TextAlign.Right else TextAlign.Left),
        maxLines = maxLines,
    )
}

/**
 * A row of a list: a marker and a line of text, in the order the line reads.
 *
 * A Persian task reads from the right, so its marker goes on the right. Glance
 * lays a row's children out in the locale's direction, so the children are
 * put in locale order: the marker comes first when the line reads the way the
 * locale does, and last when it reads the other way.
 */
@Composable
internal fun MarkedRow(
    marker: String,
    markerColor: ColorProvider,
    line: DirectedText,
    modifier: GlanceModifier = GlanceModifier,
    textColor: ColorProvider = GlanceTheme.colors.onBackground,
) {
    val localeRtl = LocalContext.current.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val markerFirst = line.rtl == localeRtl
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val markerText: @Composable () -> Unit = {
            Text(
                text = marker,
                modifier =
                    if (line.rtl) {
                        GlanceModifier.absolutePadding(left = MARKER_GAP_DP.dp)
                    } else {
                        GlanceModifier.absolutePadding(right = MARKER_GAP_DP.dp)
                    },
                style = TextStyle(color = markerColor, fontSize = ROW_SP.sp),
            )
        }
        val lineText: @Composable () -> Unit = {
            DirectedLine(
                line,
                GlanceModifier.defaultWeight(),
                TextStyle(color = textColor, fontSize = ROW_SP.sp),
                maxLines = 1,
            )
        }
        if (markerFirst) {
            markerText()
            lineText()
        } else {
            lineText()
            markerText()
        }
    }
}

/**
 * [items] stacked in a column, however many there are.
 *
 * A Glance container holds at most ten children and silently drops the rest,
 * and a tall widget asks for eighteen rows. So the rows go into columns of
 * ten, inside one column -- which looks exactly like one column.
 */
@Composable
internal fun <T> Stack(
    items: List<T>,
    modifier: GlanceModifier = GlanceModifier,
    row: @Composable (T) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.chunked(MAX_CHILDREN).forEach { chunk ->
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                chunk.forEach { row(it) }
            }
        }
    }
}

/** The most children a Glance container draws; more are dropped with a warning in logcat. */
internal const val MAX_CHILDREN = 10

/** Smaller type for what sits under a headline. */
internal const val ROW_SP = 13
internal const val HEADLINE_SP = 15
private const val ROW_GAP_DP = 3
private const val MARKER_GAP_DP = 6
internal const val WIDGET_PADDING_DP = 12

/** What the drawable gave every widget before Android 12 had a radius of its own. */
private const val WIDGET_CORNER_DP = 16
