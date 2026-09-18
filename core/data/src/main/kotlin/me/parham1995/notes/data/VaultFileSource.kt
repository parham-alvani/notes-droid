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
        private val vaults: me.parham1995.notes.data.database.VaultDao,
        private val settings: SettingsStore,
        private val tokens: TokenStore,
        private val http: OkHttpClient,
    ) {
        suspend fun bytes(
            vaultId: Long,
            path: String,
        ): ByteArray? = fetch(vaultId, path)?.second

        /**
         * The file on disk, fetching it first if it is not there yet.
         *
         * Returns a real [File] rather than bytes because the caller hands it
         * to another application, which wants something to open rather than
         * something to hold in memory -- a video in this vault is larger than
         * the heap the app is given.
         */
        suspend fun localFile(
            vaultId: Long,
            path: String,
        ): File? {
            files.fileFor(vaultId, path).takeIf { it.isFile }?.let { return it }
            fetch(vaultId, path) ?: return null
            return files.fileFor(vaultId, path).takeIf { it.isFile }
        }

        private suspend fun fetch(
            vaultId: Long,
            path: String,
        ): Pair<File, ByteArray>? {
            files.read(vaultId, path)?.let { return files.fileFor(vaultId, path) to it }

            val blob = blobs.byPath(vaultId, path) ?: return null
            val current = settings.current()
            // The policy is about images specifically: it exists to keep 300
            // photographs off the device, and says nothing about a PDF that
            // was asked for by name.
            if (blob.kind == BlobKind.IMAGE && current.imagePolicy == ImagePolicy.NEVER) return null

            // The vault's own repository, not the settings' -- those describe
            // the first vault only, and an image in the second would be fetched
            // from the wrong place.
            val vault = vaults.byId(vaultId) ?: return null
            val client =
                GitHubClient(
                    GitHubConfig(
                        owner = vault.owner,
                        repo = vault.repo,
                        branch = vault.branch,
                        token = tokens.token() ?: return null,
                    ),
                    http = http,
                )

            return withContext(Dispatchers.IO) {
                runCatching {
                    val data = client.blob(blob.sha)
                    files.write(vaultId, path, data)
                    blobs.upsert(blob.copy(localState = LocalState.DOWNLOADED))
                    files.fileFor(vaultId, path) to data
                }.getOrNull()
            }
        }
    }
