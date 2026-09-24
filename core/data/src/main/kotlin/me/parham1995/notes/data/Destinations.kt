package me.parham1995.notes.data

import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Where something asked to be taken turned out to be. */
sealed interface Destination {
    data class Note(
        val vaultId: Long,
        val noteId: Long,
        val heading: String? = null,
    ) : Destination

    /** A day with no note of its own, and the path Obsidian would give it. */
    data class NoDailyNote(
        val vaultId: Long,
        val path: String,
    ) : Destination
}

/**
 * Finds the notes that are asked for by something other than a tap on them.
 *
 * The answer always names its vault. Paths and names only mean something
 * inside one, and a caller that had to work out which afterwards is a caller
 * that could look in the wrong one.
 */
@Singleton
class Destinations
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val configs: ObsidianConfigStore,
    ) {
        /**
         * [date]'s daily note in the vault being read, where the Daily notes
         * plugin would have put it. Only found, never made: this app does not
         * create notes, and one made here would be a merge waiting to happen
         * with the one the desktop makes the same morning.
         */
        suspend fun dailyNote(date: LocalDate): Destination {
            val vaultId = repository.activeVaultId.first()
            val path = configs.dailyNotes(vaultId).pathFor(date)
            val found = repository.resolve(vaultId, path)
            return if (found != null) Destination.Note(vaultId, found.first) else Destination.NoDailyNote(vaultId, path)
        }
    }
