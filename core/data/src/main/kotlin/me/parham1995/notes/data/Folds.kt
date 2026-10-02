package me.parham1995.notes.data

/**
 * The stored form of a note's folds: block positions joined by commas.
 *
 * A column rather than a table, because a fold has no life of its own -- it
 * is a mark on a note, like where it was left -- and a table hanging off
 * `notes` without a `vaultId` is one more thing `removeNotes` has to remember.
 */
object Folds {
    fun parse(raw: String): Set<Int> =
        raw
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .toSet()

    fun format(folded: Set<Int>): String = folded.sorted().joinToString(",")
}
