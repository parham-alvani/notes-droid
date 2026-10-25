package me.parham1995.notes.feature.note

import me.parham1995.notes.data.database.HeadingEntity

/** Where the page goes when a note arrives, or a heading in it is asked for. */
internal sealed interface Landing {
    /** To the block at this position in the note. */
    data class To(
        val block: Int,
    ) : Landing

    /** Nowhere: the page is already where the reader put it. */
    data object Stay : Landing
}

/**
 * Where a note lands.
 *
 * A heading asked for wins over where the note was left: it is the reason the
 * note was opened at all. Where it was left is only for the first landing on a
 * note -- once the reader is in it, a jump to a heading in the same note must
 * not be followed by a jump back to the old place. It was: following
 * `[[#Heading]]` scrolled there, cleared the heading, and the effect that ran
 * again for the clearing restored the stored position, so the page bounced to
 * wherever the note had been left.
 *
 * The heading is done with whichever way this goes, found or not.
 *
 * A first landing always goes to [resumeAt], even the top: the list is shared
 * by every note this screen shows, and a note never scrolled would otherwise
 * open at the place the last one was left.
 */
internal fun landing(
    headings: List<HeadingEntity>,
    heading: String?,
    blockRefs: Map<String, Int>,
    resumeAt: Int,
    firstLanding: Boolean,
): Landing {
    val target = blockForHeading(headings, heading, blockRefs)
    return when {
        target != null -> Landing.To(target)
        // A heading renamed since lands where the note was left, if anywhere.
        firstLanding -> Landing.To(resumeAt.coerceAtLeast(0))
        else -> Landing.Stay
    }
}
