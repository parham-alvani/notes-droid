package me.parham1995.notes.data

import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.sync.NoteVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A note's history and its text at one commit, for the vault the note is in
 * -- a REST vault asks GitHub, an SSH vault its clone. Restoring a version is
 * a write, and goes through [VaultWriteRepository.restore].
 */
@Singleton
class NoteHistoryRepository
    @Inject
    constructor(
        private val vaults: VaultDao,
        private val transports: VaultTransports,
    ) {
        /** The commits that changed [path] in [vaultId], newest first, across renames. */
        suspend fun history(
            vaultId: Long,
            path: String,
        ): List<NoteVersion> {
            val vault = vaults.byId(vaultId) ?: return emptyList()
            return transports.history(vault).history(path)
        }

        /** The note as it was at [version]; null when that commit does not have it. */
        suspend fun content(
            vaultId: Long,
            version: NoteVersion,
        ): String? {
            val vault = vaults.byId(vaultId) ?: return null
            return transports.history(vault).content(version)
        }
    }
