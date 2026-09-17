package me.parham1995.notes.markdown

import java.text.Normalizer
import java.util.Locale

/**
 * Normalisation shared by the link resolver and the index.
 *
 * Always NFC and always [Locale.ROOT]. A Turkish-locale lowercase turns the
 * dotted capital I of a filename like a Turkish dish into something that never
 * matches again, and a vault with accented filenames needs both sides of a
 * comparison in the same Unicode composition.
 */
object Slugs {
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    fun fold(text: String): String = normalize(text).lowercase(Locale.ROOT)

    /**
     * A heading anchor. Obsidian matches heading text literally rather than
     * slugifying it, so this only folds case and collapses whitespace -- em
     * dashes and parentheses in real anchors have to survive.
     */
    fun heading(text: String): String = fold(text).replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("""\s+""")
}
