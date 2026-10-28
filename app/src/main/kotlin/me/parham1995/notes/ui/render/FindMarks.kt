package me.parham1995.notes.ui.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.NoteSearch
import me.parham1995.notes.ui.theme.accents

/**
 * What find in the note is looking for, and which hit the bar is on.
 *
 * [block] is the hit's block by its position in the note, and [ordinal] which
 * hit inside that block it is, counted the way `NoteSearch.find` counts them.
 */
@Immutable
data class FindTarget(
    val query: String,
    val block: Int,
    val ordinal: Int,
)

/**
 * The marks one block's text carries while find is open.
 *
 * Every hit is tinted; the current one is drawn solid, the way the Mac reader
 * draws it, so the eye lands on it among the others.
 */
@Immutable
data class FindMarks(
    val query: String,
    /** Which hit in this text is the current one; null when none of them is. */
    val current: Int? = null,
    /**
     * Every hit in this text is the current one: a block drawn as several
     * texts -- a list, a table, a callout -- cannot tell which of its pieces
     * the bar's count reached, so the whole block's hits stand out together.
     */
    val everyCurrent: Boolean = false,
) {
    companion object {
        /** The marks for the block at [index], or null while nothing is looked for. */
        fun forBlock(
            target: FindTarget?,
            index: Int,
            block: MdBlock,
        ): FindMarks? {
            if (target == null || target.query.isBlank()) return null
            if (index != target.block) return FindMarks(target.query)
            return when (block) {
                // One text each, so the ordinal is the hit's place in it. A
                // list hands it on, item by item, to the text that holds it.
                is MdBlock.Paragraph, is MdBlock.Heading, is MdBlock.CodeBlock, is MdBlock.ListBlock ->
                    FindMarks(target.query, current = target.ordinal)
                else -> FindMarks(target.query, everyCurrent = true)
            }
        }
    }
}

/**
 * The marks for each block of each item of [list]: the current hit goes to the
 * one block that holds it, counted in the order `NoteSearch.find` counts, and
 * the others mark their hits plainly. A long task list is one block, and
 * stepping through its hits has to reach each item, not light them all up.
 */
fun FindMarks.forItems(list: MdBlock.ListBlock): List<List<FindMarks>> {
    var before = 0
    return list.items.map { item ->
        item.blocks.map { child ->
            val hits = NoteSearch.count(child, query)
            val at = current?.minus(before)?.takeIf { it in 0 until hits }
            before += hits
            copy(current = at)
        }
    }
}

/** The find marks this text is drawn with; null outside the open note's find. */
val LocalFind = compositionLocalOf<FindMarks?> { null }

/**
 * Told where the current hit is drawn, its top and bottom in root
 * coordinates, by the text that holds it. Null where nothing scrolls to it.
 */
val LocalFindReport = compositionLocalOf<((top: Float, bottom: Float) -> Unit)?> { null }

/** Where a text was last placed, kept out of state: it changes on every scroll. */
private class Placed {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Tells [LocalFindReport] where this text's current hit is drawn, once the
 * text is laid out with its marks and placed -- and again as it moves -- so
 * the page can bring the hit itself on screen, not only the top of its block.
 */
@Composable
internal fun Modifier.findReport(
    text: AnnotatedString,
    marks: FindMarks?,
    layout: TextLayoutResult?,
): Modifier {
    val report = LocalFindReport.current
    val at = marks?.let { if (it.everyCurrent) 0 else it.current }
    val range =
        remember(text, marks?.query, at) {
            if (marks == null || at == null) null else NoteSearch.ranges(text.text, marks.query).getOrNull(at)
        }
    val placed = remember { Placed() }

    fun send() {
        val coordinates = placed.coordinates?.takeIf { it.isAttached } ?: return
        if (report == null || range == null || layout == null) return
        if (range.first >= layout.layoutInput.text.length) return
        val box = layout.getBoundingBox(range.first)
        val top = coordinates.localToRoot(Offset(0f, box.top)).y
        report(top, top + box.height)
    }
    LaunchedEffect(report, range, layout) { send() }
    if (report == null || range == null) return this
    return onGloballyPositioned {
        placed.coordinates = it
        send()
    }
}

/** The tints the hits are drawn in: every one, and the current one. */
@Immutable
data class FindColors(
    val hit: Color,
    val current: Color,
    val onCurrent: Color,
)

/**
 * The Mac reader's: vivid yellow, faint, behind every hit, and the accent
 * behind the current one with the page's own colour on it. From the scheme in
 * force, so code -- always on naz's dark panel -- is marked in naz's colours.
 */
@Composable
internal fun findColors(): FindColors {
    val scheme = MaterialTheme.colorScheme
    return FindColors(
        hit = scheme.accents.vividYellow.copy(alpha = FIND_HIT_ALPHA),
        current = scheme.primary,
        onCurrent = scheme.background,
    )
}

private const val FIND_HIT_ALPHA = 0.38f

/**
 * [this] with each hit of [marks] painted behind, found the way the find bar
 * counts them so the page and the count agree.
 */
fun AnnotatedString.withFindMarks(
    marks: FindMarks?,
    colors: FindColors,
): AnnotatedString {
    if (marks == null) return this
    val hits = NoteSearch.ranges(text, marks.query)
    if (hits.isEmpty()) return this
    return buildAnnotatedString {
        append(this@withFindMarks)
        hits.forEachIndexed { at, range ->
            val current = marks.everyCurrent || at == marks.current
            addStyle(
                if (current) {
                    SpanStyle(background = colors.current, color = colors.onCurrent)
                } else {
                    SpanStyle(background = colors.hit)
                },
                range.first,
                range.last + 1,
            )
        }
    }
}
