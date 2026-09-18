package me.parham1995.notes.data

import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.BlobEntity
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.VaultEntry
import me.parham1995.notes.sync.VaultFilter
import me.parham1995.notes.sync.VaultSink
import javax.inject.Inject

/**
 * Lands synced bytes on disk and in the manifest.
 *
 * The file is written before its manifest row is upserted, deliberately. The
 * two cannot share a transaction, and this order is the safe one: a sync killed
 * in between leaves a file the manifest does not know about, so the next sync
 * fetches it again -- wasteful but correct. The reverse would record a note as
 * present when its bytes never landed.
 */
class RoomVaultSink
    @Inject
    constructor(
        private val files: VaultFileStore,
        private val blobs: BlobDao,
    ) : VaultSink {
        /** Filled in by the caller so kind and size survive into the manifest. */
        var plannedEntries: Map<String, VaultEntry> = emptyMap()

        /**
         * Which vault the rows being written belong to. Paths are relative to
         * it, so nothing here has to translate them.
         */
        var vaultId: Long = 0

        /**
         * Only used when a write arrives with no planned entry behind it. The
         * kind decides whether the file is later parsed as a note, so guessing
         * markdown here would index the Iconic config as one.
         */
        private val filter = VaultFilter()

        override suspend fun write(
            path: String,
            bytes: ByteArray,
            sha: String,
        ) {
            files.write(vaultId, path, bytes)
            val planned = plannedEntries[path]
            blobs.upsert(
                BlobEntity(
                    path = path,
                    sha = sha,
                    size = planned?.size?.takeIf { it > 0 } ?: bytes.size.toLong(),
                    vaultId = vaultId,
                    kind = planned?.kind ?: filter.kindOf(path) ?: BlobKind.OTHER,
                    localState = LocalState.DOWNLOADED,
                ),
            )
        }

        override suspend fun record(
            entry: VaultEntry,
            state: LocalState,
        ) {
            blobs.upsert(
                BlobEntity(
                    path = entry.path,
                    vaultId = vaultId,
                    sha = entry.sha,
                    size = entry.size,
                    kind = entry.kind,
                    localState = state,
                ),
            )
        }

        override suspend fun move(
            from: String,
            to: String,
        ) {
            files.move(vaultId, from, to)
            blobs.rename(vaultId, from, to)
        }

        override suspend fun delete(path: String) {
            files.delete(vaultId, path)
            blobs.deleteByPath(vaultId, path)
        }
    }
