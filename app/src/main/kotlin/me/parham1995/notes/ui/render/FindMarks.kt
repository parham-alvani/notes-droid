package me.parham1995.notes.ui.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
                // One text each, so the ordinal is the hit's place in it.
                is MdBlock.Paragraph, is MdBlock.Heading, is MdBlock.CodeBlock ->
                    FindMarks(target.query, current = target.ordinal)
                else -> FindMarks(target.query, everyCurrent = true)
            }
        }
    }
}

/** The find marks this text is drawn with; null outside the open note's find. */
val LocalFind = compositionLocalOf<FindMarks?> { null }

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
