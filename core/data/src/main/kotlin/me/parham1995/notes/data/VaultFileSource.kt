package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.GitHubClient
import me.parham1995.notes.sync.GitHubConfig
import me.parham1995.notes.sync.LocalState
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the bytes behind anything that is not markdown.
 *
 * Images and attachments are recorded during sync but not downloaded, which is
 * what keeps a default install to the markdown alone. The first time something
 * asks for one it is fetched by its sha and written to the vault, so it is
 * local from then on -- and under the SSH transport it is already there,
 * because a clone has no way to leave it out.
 */
@Singleton
class VaultFileSource
    @Inject
    constructor(
        private val files: VaultFileStore,
        private val blobs: BlobDao,
        private val settings: SettingsStore,
        private val tokens: TokenStore,
        private val http: OkHttpClient,
    ) {
        suspend fun bytes(path: String): ByteArray? = fetch(path)?.second

        /**
         * The file on disk, fetching it first if it is not there yet.
         *
         * Returns a real [File] rather than bytes because the caller hands it
         * to another application, which wants something to open rather than
         * something to hold in memory -- a video in this vault is larger than
         * the heap the app is given.
         */
        suspend fun localFile(path: String): File? {
            files.fileFor(path).takeIf { it.isFile }?.let { return it }
            fetch(path) ?: return null
            return files.fileFor(path).takeIf { it.isFile }
        }

        private suspend fun fetch(path: String): Pair<File, ByteArray>? {
            files.read(path)?.let { return files.fileFor(path) to it }

            val blob = blobs.byPath(path) ?: return null
            val current = settings.current()
            if (!current.isConfigured) return null
            // The policy is about images specifically: it exists to keep 300
            // photographs off the device, and says nothing about a PDF that
            // was asked for by name.
            if (blob.kind == BlobKind.IMAGE && current.imagePolicy == ImagePolicy.NEVER) return null

            val client =
                GitHubClient(
                    GitHubConfig(
                        owner = current.owner,
                        repo = current.repo,
                        branch = current.branch,
                        token = tokens.token() ?: return null,
                    ),
                    http = http,
                )

            return withContext(Dispatchers.IO) {
                runCatching {
                    val data = client.blob(blob.sha)
                    files.write(path, data)
                    blobs.upsert(blob.copy(localState = LocalState.DOWNLOADED))
                    files.fileFor(path) to data
                }.getOrNull()
            }
        }
    }
