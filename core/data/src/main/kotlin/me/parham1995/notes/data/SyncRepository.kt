package me.parham1995.notes.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.SyncStateDao
import me.parham1995.notes.data.database.SyncStateEntity
import me.parham1995.notes.data.git.GitSshVaultSync
import me.parham1995.notes.data.git.SshKeyStore
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.GitHubClient
import me.parham1995.notes.sync.GitHubConfig
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.RepositoryInfo
import me.parham1995.notes.sync.RestVaultSync
import me.parham1995.notes.sync.SyncBase
import me.parham1995.notes.sync.SyncPlan
import me.parham1995.notes.sync.VaultFilter
import me.parham1995.notes.sync.VaultSync
import okhttp3.OkHttpClient
import java.io.File
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
        private val indexer: VaultIndexer,
        private val log: SyncLog,
        private val files: VaultFileStore,
        private val sshKeys: SshKeyStore,
        @param:ApplicationContext private val context: Context,
        private val http: OkHttpClient,
    ) {
        val status: Flow<SyncStatus> =
            syncState.observe().map {
                SyncStatus(it?.headCommit, it?.lastSyncAt, it?.lastError)
            }

        val noteCount: Flow<Int> = blobs.countOfKind(BlobKind.MARKDOWN)

        /** Confirms the token and repository before anything is synced. */
        suspend fun testConnection(): RepositoryInfo = client().repository()

        /**
         * Runs one sync. [onProgress] reports units of work done, which is the
         * honest measure here -- the compare endpoint does not carry file
         * sizes, so byte-level progress would be a guess.
         */
        suspend fun sync(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SyncPlan =
            coroutineScope {
                syncInScope(this, onProgress)
            }

        private suspend fun syncInScope(
            scope: CoroutineScope,
            onProgress: (done: Int, total: Int) -> Unit,
        ): SyncPlan {
            val startedAt = System.currentTimeMillis()
            val current = settings.current()
            log.info("sync started - ${current.owner}/${current.repo} over ${current.transport}")
            val transport = transportFor(current)

            val stored = syncState.get()
            val base =
                SyncBase(
                    commit = stored?.headCommit,
                    manifest = blobs.manifestRows().associate { it.path to it.sha },
                    etagRef = stored?.etagRef,
                )

            log.info("at ${stored?.headCommit?.take(7) ?: "nothing yet"}, ${base.manifest.size} files tracked")

            val narrator =
                (transport as? GitSshVaultSync)?.let { git ->
                    scope.launch {
                        var previous = ""
                        git.progress.stage.collect { stage ->
                            if (stage.isNotEmpty() && stage != previous) {
                                previous = stage
                                log.info(stage)
                            }
                        }
                    }
                }

            try {
                val plan = transport.plan(base)
                if (plan.isEmpty) {
                    log.info("nothing changed upstream")
                } else {
                    log.info(
                        "plan: +${plan.adds.size} ~${plan.modifies.size} " +
                            "moved ${plan.renames.size} -${plan.deletes.size} " +
                            "(${plan.unchanged} unchanged) -> ${plan.headCommit.take(7)}",
                    )
                }
                if (!plan.isEmpty) {
                    val sink = sinkProvider.get()
                    sink.plannedEntries = plan.downloads.associateBy { it.path }
                    transport.apply(plan, sink, onProgress)
                    log.info("downloaded ${plan.downloads.size} files")

                    val indexStart = System.currentTimeMillis()
                    index(plan, firstSync = stored?.headCommit == null)
                    log.info("indexed in ${(System.currentTimeMillis() - indexStart) / 1000}s")
                }
                syncState.upsert(
                    SyncStateEntity(
                        headCommit = plan.headCommit.takeIf { it.isNotEmpty() } ?: stored?.headCommit,
                        etagRef = plan.etagRef,
                        lastSyncAt = System.currentTimeMillis(),
                        lastError = null,
                    ),
                )
                log.info("sync finished in ${(System.currentTimeMillis() - startedAt) / 1000}s")
                return plan
            } catch (failure: Exception) {
                log.error(failure.describeChain())
                syncState.upsert(
                    SyncStateEntity(
                        headCommit = stored?.headCommit,
                        etagRef = stored?.etagRef,
                        lastSyncAt = stored?.lastSyncAt,
                        lastError = failure.message ?: failure::class.simpleName,
                    ),
                )
                throw failure
            } finally {
                narrator?.cancel()
            }
        }

        /**
         * Parses what just arrived into the searchable index.
         *
         * A first sync indexes everything in one pass; afterwards only the
         * files that actually changed are reparsed, which keeps a routine
         * refresh to well under a second.
         */
        private suspend fun index(
            plan: SyncPlan,
            firstSync: Boolean,
        ) {
            val markdown =
                blobs
                    .byKindAndState(BlobKind.MARKDOWN, LocalState.DOWNLOADED)
                    .map { PathAndSha(it.path, it.sha) }

            if (firstSync) {
                indexer.indexAll(markdown)
                return
            }

            val touched =
                (plan.adds + plan.modifies).map { it.path }.toSet() +
                    plan.renames.map { it.to }.toSet()
            indexer.indexChanged(
                changed = markdown.filter { it.path in touched },
                removed = plan.deletes + plan.renames.map { it.from },
            )
        }

        /** Forgets everything so the next sync starts from nothing. */
        suspend fun reset() {
            blobs.clear()
            syncState.clear()
            log.warn("vault reset - the next sync starts from nothing")
        }

        private companion object {
            const val DEFAULT_BRANCH = "main"
        }

        /**
         * The chosen transport, built fresh each sync so a settings change
         * takes effect on the next refresh rather than on the next launch.
         */
        private suspend fun transportFor(current: VaultSettings): VaultSync =
            when (current.transport) {
                SyncTransport.REST -> {
                    val client = client()
                    RestVaultSync(
                        client = client,
                        branch = current.branch ?: client.repository().defaultBranch,
                        filter = VaultFilter(),
                        log = log::info,
                    )
                }

                SyncTransport.SSH -> {
                    if (!sshKeys.exists()) throw NotConfiguredException("no SSH key generated yet")
                    GitSshVaultSync(
                        workTree = files.root,
                        remoteUrl = sshUrl(current),
                        branch = current.branch ?: DEFAULT_BRANCH,
                        keys = sshKeys,
                        configDir = File(context.filesDir, "git"),
                        log = log::info,
                    )
                }
            }

        /**
         * GitHub answers SSH on 443 as well as 22, which is the way round a
         * network that blocks 22 -- otherwise the clone just hangs.
         */
        private fun sshUrl(current: VaultSettings): String =
            if (current.sshOverPort443) {
                "ssh://git@ssh.github.com:443/" + current.owner + "/" + current.repo + ".git"
            } else {
                "git@github.com:" + current.owner + "/" + current.repo + ".git"
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
