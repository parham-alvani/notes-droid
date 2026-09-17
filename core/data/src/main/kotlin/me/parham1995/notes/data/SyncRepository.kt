package me.parham1995.notes.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.SyncStateDao
import me.parham1995.notes.data.database.SyncStateEntity
import me.parham1995.notes.sync.GitHubClient
import me.parham1995.notes.sync.GitHubConfig
import me.parham1995.notes.sync.RepositoryInfo
import me.parham1995.notes.sync.RestVaultSync
import me.parham1995.notes.sync.SyncBase
import me.parham1995.notes.sync.SyncPlan
import me.parham1995.notes.sync.VaultFilter
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Why a sync could not start. Distinct from the transport's own failures. */
class NotConfiguredException(
    message: String,
) : IllegalStateException(message)

/** Where the device is up to, for the UI. */
data class SyncStatus(
    val headCommit: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
) {
    val hasSynced: Boolean get() = headCommit != null
}

@Singleton
class SyncRepository
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val tokens: TokenStore,
        private val blobs: BlobDao,
        private val syncState: SyncStateDao,
        private val sinkProvider: Provider<RoomVaultSink>,
        private val http: OkHttpClient,
    ) {
        val status: Flow<SyncStatus> =
            syncState.observe().map {
                SyncStatus(it?.headCommit, it?.lastSyncAt, it?.lastError)
            }

        val noteCount: Flow<Int> = blobs.countOfKind(me.parham1995.notes.sync.BlobKind.MARKDOWN)

        /** Confirms the token and repository before anything is synced. */
        suspend fun testConnection(): RepositoryInfo = client().repository()

        /**
         * Runs one sync. [onProgress] reports units of work done, which is the
         * honest measure here -- the compare endpoint does not carry file
         * sizes, so byte-level progress would be a guess.
         */
        suspend fun sync(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SyncPlan {
            val current = settings.current()
            val client = client()
            val branch = current.branch ?: client.repository().defaultBranch
            val transport = RestVaultSync(client, branch, VaultFilter())

            val stored = syncState.get()
            val base =
                SyncBase(
                    commit = stored?.headCommit,
                    manifest = blobs.manifestRows().associate { it.path to it.sha },
                    etagRef = stored?.etagRef,
                )

            try {
                val plan = transport.plan(base)
                if (!plan.isEmpty) {
                    val sink = sinkProvider.get()
                    sink.plannedEntries = plan.downloads.associateBy { it.path }
                    transport.apply(plan, sink, onProgress)
                }
                syncState.upsert(
                    SyncStateEntity(
                        headCommit = plan.headCommit.takeIf { it.isNotEmpty() } ?: stored?.headCommit,
                        etagRef = plan.etagRef,
                        lastSyncAt = System.currentTimeMillis(),
                        lastError = null,
                    ),
                )
                return plan
            } catch (failure: Exception) {
                syncState.upsert(
                    SyncStateEntity(
                        headCommit = stored?.headCommit,
                        etagRef = stored?.etagRef,
                        lastSyncAt = stored?.lastSyncAt,
                        lastError = failure.message ?: failure::class.simpleName,
                    ),
                )
                throw failure
            }
        }

        /** Forgets everything so the next sync starts from nothing. */
        suspend fun reset() {
            blobs.clear()
            syncState.clear()
        }

        private suspend fun client(): GitHubClient {
            val current = settings.current()
            if (!current.isConfigured) throw NotConfiguredException("no repository configured")
            val token = tokens.token() ?: throw NotConfiguredException("no access token stored")
            return GitHubClient(
                GitHubConfig(
                    owner = current.owner,
                    repo = current.repo,
                    branch = current.branch,
                    token = token,
                ),
                http = http,
            )
        }
    }
