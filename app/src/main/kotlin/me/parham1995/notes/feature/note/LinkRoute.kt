package me.parham1995.notes.feature.note

import me.parham1995.notes.markdown.MarkdownLinks

/** Where a Markdown link inside the vault goes. */
sealed interface LinkRoute {
    /** `[see below](#Details)`: a heading in the note being read. */
    data class Here(
        val heading: String?,
    ) : LinkRoute

    data class Note(
        val noteId: Long,
        val heading: String?,
    ) : LinkRoute

    /** A file in the vault, by its vault path: `[the scan](../uploads/scan.pdf)`. */
    data class File(
        val path: String,
    ) : LinkRoute

    /** Nothing answers to it; [target] is what to name when saying so. */
    data class Nowhere(
        val target: String,
    ) : LinkRoute
}

/**
 * Where `[text](destination)` goes, for a destination with no scheme.
 *
 * These used to be handed to the system as web addresses, which opened nothing
 * and said nothing: `Other%20Note.md`, `folder/Note` and `#Heading` are all
 * inside the vault. The target is decoded and looked up exactly as the index
 * looked it up -- [targetOf] is the note's own table of resolved links -- so a
 * link that is shown working opens.
 */
internal fun routeOf(
    destination: String,
    sourcePath: String,
    targetOf: (String) -> Long?,
): LinkRoute {
    val link = MarkdownLinks.internal(destination)
    if (link.target.isEmpty()) return LinkRoute.Here(link.heading)
    targetOf(link.target)?.let { return LinkRoute.Note(it, link.heading) }
    if (MarkdownLinks.isFile(link.target)) return LinkRoute.File(MarkdownLinks.fromNote(link.target, sourcePath))
    return LinkRoute.Nowhere(link.target)
}
