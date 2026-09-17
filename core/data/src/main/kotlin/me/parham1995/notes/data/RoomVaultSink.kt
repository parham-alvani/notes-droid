package me.parham1995.notes.data

import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.BlobEntity
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.VaultEntry
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

        override suspend fun write(
            path: String,
            bytes: ByteArray,
            sha: String,
        ) {
            files.write(path, bytes)
            val planned = plannedEntries[path]
            blobs.upsert(
                BlobEntity(
                    path = path,
                    sha = sha,
                    size = planned?.size?.takeIf { it > 0 } ?: bytes.size.toLong(),
                    kind = planned?.kind ?: me.parham1995.notes.sync.BlobKind.MARKDOWN,
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
            files.move(from, to)
            blobs.rename(from, to)
        }

        override suspend fun delete(path: String) {
            files.delete(path)
            blobs.deleteByPath(path)
        }
    }
