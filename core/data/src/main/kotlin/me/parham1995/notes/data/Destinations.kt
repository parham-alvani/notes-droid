package me.parham1995.notes.data

import kotlinx.coroutines.flow.first
import me.parham1995.notes.obsidian.ObsidianLink
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
