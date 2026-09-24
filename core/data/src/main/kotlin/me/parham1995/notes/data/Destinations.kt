package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.NoteRef
import me.parham1995.notes.obsidian.ObsidianLink
import me.parham1995.notes.obsidian.Period
import me.parham1995.notes.obsidian.PeriodicNotes
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

    /**
     * A day with no note of its own, and the path Obsidian would give it --
     * the note of the day's week or month, when that is what the format
     * names a note for.
     */
    data class NoDailyNote(
        val vaultId: Long,
        val path: String,
        val period: Period = Period.DAY,
    ) : Destination

    data class Search(
        val vaultId: Long,
        val query: String,
    ) : Destination

    /** A vault, to be read from its root. */
    data class Vault(
        val vaultId: Long,
    ) : Destination

    /** A link naming a vault that is not configured here. */
    data class UnknownVault(
        val name: String,
    ) : Destination

    /** A link naming a note [vaultId] does not have. */
    data class UnknownNote(
        val vaultId: Long,
        val file: String,
    ) : Destination
}

/**
 * A daily note's place in its vault's series: the notes either side of it
 * that exist. Either is null at the end of the series.
 */
data class PeriodNeighbours(
    val vaultId: Long,
    val period: Period,
    val previous: NoteRef?,
    val next: NoteRef?,
)

/**
 * Finds the notes that are asked for by something other than a tap on them:
 * today's, and whatever an `obsidian://` link names.
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
        private val notes: NoteDao,
    ) {
        /**
         * [date]'s daily note in the vault being read, where the Daily notes
         * plugin would have put it. Only found, never made: this app does not
         * create notes, and one made here would be a merge waiting to happen
         * with the one the desktop makes the same morning.
         */
        suspend fun dailyNote(date: LocalDate): Destination {
            val vaultId = repository.activeVaultId.first()
            val config = configs.dailyNotes(vaultId)
            val path = config.pathFor(date)
            val found = repository.resolve(vaultId, path)
            return if (found != null) {
                Destination.Note(vaultId, found.first)
            } else {
                Destination.NoDailyNote(vaultId, path, PeriodicNotes(config).period)
            }
        }

        /**
         * How long a daily note of the vault being read covers, so the button
         * that opens one can say "this week" when that is what it opens.
         */
        fun dailyPeriod(): Flow<Period> =
            configs
                .dailyNotes(repository.activeVaultId)
                .map { PeriodicNotes(it).period }
                .distinctUntilChanged()

        /**
         * The daily notes before and after [path] in [vaultId], or null when
         * [path] is not one.
         *
         * The nearest that exist rather than the adjacent periods: a weekly
         * journal kept in fits and starts has gaps, and the note on the far
         * side of one is more use than being told the week before has none.
         * Only [vaultId]'s notes are looked at -- a daily note in another
         * vault is another journal.
         */
        suspend fun neighbours(
            vaultId: Long,
            path: String,
        ): PeriodNeighbours? {
            val series = PeriodicNotes(configs.dailyNotes(vaultId))
            if (series.periodOf(path) == null) return null
            val refs = notes.allIds(vaultId)
            val paths = refs.map { it.path }

            fun ref(step: Int) = series.nearest(paths, path, step)?.let { found -> refs.first { it.path == found } }
            // Every note's name is read against the format: cheap, but a
            // vault's worth of it, and asked for as a note opens.
            return withContext(Dispatchers.Default) {
                PeriodNeighbours(vaultId, series.period, previous = ref(-1), next = ref(1))
            }
        }

        /**
         * Where an `obsidian://` link points, in the vault it names -- or the
         * vault being read, when it names none, which is what Obsidian does
         * too. Looked up only: switching to the vault is the caller's to do.
         */
        suspend fun follow(link: ObsidianLink): Destination {
            val vaultId =
                link.vault?.let { name -> repository.vaultNamed(name)?.id ?: return Destination.UnknownVault(name) }
                    ?: repository.activeVaultId.first()
            return when (link) {
                is ObsidianLink.Search -> Destination.Search(vaultId, link.query)
                is ObsidianLink.Open -> {
                    val file = link.file ?: return Destination.Vault(vaultId)
                    val noteId = repository.find(vaultId, file) ?: return Destination.UnknownNote(vaultId, file)
                    Destination.Note(vaultId, noteId, link.heading)
                }
            }
        }
    }
