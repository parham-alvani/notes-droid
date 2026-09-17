package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.sync.GitHubClient
import me.parham1995.notes.sync.GitHubConfig
import me.parham1995.notes.sync.LocalState
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the bytes behind an embedded image.
 *
 * Images are recorded during sync but not downloaded, which is what keeps a
 * default install to the markdown alone. The first time a note that embeds one
 * is opened, the blob is fetched by its sha and written to the vault, so it is
 * local from then on.
 */
@Singleton
class VaultImageSource
    @Inject
    constructor(
        private val files: VaultFileStore,
        private val blobs: BlobDao,
        private val settings: SettingsStore,
        private val tokens: TokenStore,
        private val http: OkHttpClient,
    ) {
        suspend fun bytes(path: String): ByteArray? {
            files.read(path)?.let { return it }

            val blob = blobs.byPath(path) ?: return null
            val current = settings.current()
            if (!current.isConfigured) return null
            if (current.imagePolicy == ImagePolicy.NEVER) return null

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
                    data
                }.getOrNull()
            }
        }
    }
