package me.parham1995.notes.data

/**
 * The stored form of a note's folds: block positions joined by commas.
 *
 * A column rather than a table, because a fold has no life of its own -- it
 * is a mark on a note, like where it was left -- and a table hanging off
 * `notes` without a `vaultId` is one more thing `removeNotes` has to remember.
 *
 * Three things are told apart, because where notes open folded they open
 * differently (`folded-sections.md` in notes-linux's `docs/specs/`): no list
 * kept (the empty string, which is also what every note had before), a note
 * the person never folded or opened, which opens as the settings say; an
 * empty list kept ([NONE_SHUT]), a note they opened whole, which stays open;
 * and the positions they left shut.
 */
object Folds {
    /** An empty list, kept on purpose. Not a number, so an older reading of it is no fold. */
    const val NONE_SHUT = "-"

    /** The folds kept in [raw], or null when none were ever kept. */
    fun parse(raw: String): Set<Int>? {
        if (raw.isBlank()) return null
        return raw
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .toSet()
    }

    /**
     * [folded] as it is stored. [explicit] keeps "none shut" as the person's
     * choice, which it is while notes open folded; while they open unfolded
     * it is the default again and nothing is kept.
     */
    fun format(
        folded: Set<Int>,
        explicit: Boolean = false,
    ): String =
        when {
            folded.isNotEmpty() -> folded.sorted().joinToString(",")
            explicit -> NONE_SHUT
            else -> ""
        }
}
