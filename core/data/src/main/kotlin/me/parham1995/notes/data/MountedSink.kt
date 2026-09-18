package me.parham1995.notes.data

import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.VaultEntry
import me.parham1995.notes.sync.VaultSink

/**
 * Puts a repository's files where its mount says they go.
 *
 * The transports speak repository-relative paths and know nothing about where
 * a repository sits on the device, which is exactly the separation worth
 * keeping: adding a second repository changed nothing in either of them. This
 * translates on the way in, and is the only place that does.
 *
 * For the root-mounted vault -- the one an existing install already has -- this
 * is the identity, so nothing about its behaviour changes.
 */
class MountedSink(
    private val vault: VaultEntity,
    private val delegate: RoomVaultSink,
) : VaultSink {
    init {
        delegate.vaultId = vault.id
    }

    var plannedEntries: Map<String, VaultEntry>
        get() = delegate.plannedEntries
        set(value) {
            delegate.plannedEntries =
                value
                    .mapKeys { (path, _) -> vault.mounted(path) }
                    .mapValues { (_, entry) -> entry.copy(path = vault.mounted(entry.path)) }
        }

    override suspend fun write(
        path: String,
        bytes: ByteArray,
        sha: String,
    ) = delegate.write(vault.mounted(path), bytes, sha)

    override suspend fun record(
        entry: VaultEntry,
        state: LocalState,
    ) = delegate.record(entry.copy(path = vault.mounted(entry.path)), state)

    override suspend fun move(
        from: String,
        to: String,
    ) = delegate.move(vault.mounted(from), vault.mounted(to))

    override suspend fun delete(path: String) = delegate.delete(vault.mounted(path))
}
