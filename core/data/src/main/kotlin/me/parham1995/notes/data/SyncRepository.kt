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
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
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
        private val vaults: VaultDao,
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

        /** Every repository this app reads, in the order they are shown. */
        fun vaults(): Flow<List<VaultEntity>> = vaults.observe()

        /**
         * Moves the single repository from settings into the table, once.
         *
         * The app read one repository configured in a preferences file before
         * it could read several. Rather than migrating that in SQL -- which
         * cannot see a DataStore -- the first sync after the upgrade copies it
         * in, mounted at the root so none of its paths change and nothing has
         * to move on disk.
         */
        suspend fun ensureSeeded() {
            if (vaults.count() > 0) return
            val current = settings.current()
            if (!current.isConfigured) return
            val id =
                vaults.insert(
                    VaultEntity(
                        owner = current.owner,
                        repo = current.repo,
                        branch = current.branch,
                        mount = "",
                        transport = current.transport.name,
                        ordinal = 0,
                    ),
                )
            // Everything already on the device came from this repository, and
            // saying so is the difference between one quiet refresh and
            // re-downloading the whole vault.
            blobs.adoptOrphans(id)
            val stored = syncState.get()
            if (stored != null) {
                vaults.update(
                    vaults.byId(id)!!.copy(
                        headCommit = stored.headCommit,
                        etagRef = stored.etagRef,
                        lastSyncAt = stored.lastSyncAt,
                        filterVersion = stored.filterVersion,
                        indexVersion = stored.indexVersion,
                    ),
                )
            }
            log.info("carried the existing repository over as the root vault")
        }

        suspend fun addVault(
            owner: String,
            repo: String,
            branch: String?,
            mount: String,
            transport: SyncTransport,
        ): Long {
            val existing = vaults.all()
            return vaults.insert(
                VaultEntity(
                    owner = owner.trim(),
                    repo = repo.trim(),
                    branch = branch?.trim()?.takeIf { it.isNotBlank() },
                    mount = mount.trim().trim('/'),
                    transport = transport.name,
                    ordinal = existing.size,
                ),
            )
        }

        suspend fun updateVault(vault: VaultEntity) = vaults.update(vault)

        /**
         * Forgets a repository and everything it brought with it.
         *
         * The notes are removed through the indexer rather than by deleting
         * rows, so the search index and the links pointing at them go too --
         * a note deleted from `notes` alone leaves an FTS row that still
         * matches and a backlink that still resolves.
         */
        suspend fun removeVault(id: Long) {
            val vault = vaults.byId(id) ?: return
            val owned = blobs.byVaultKindAndState(id, BlobKind.MARKDOWN, LocalState.DOWNLOADED).map { it.path }
            indexer.indexChanged(changed = emptyList(), removed = owned)
            blobs.clearVault(id)
            if (vault.mount.isNotEmpty()) files.deleteTree(vault.mount)
            vaults.delete(id)
            log.warn("removed ${vault.owner}/${vault.repo}")
        }

        /** Confirms the token and repository before anything is synced. */
        suspend fun testConnection(): RepositoryInfo {
            ensureSeeded()
            val vault =
                vaults.all().firstOrNull { it.enabled }
                    ?: throw NotConfiguredException("no repository configured")
            return client(vault).repository()
        }

        /**
         * Runs one sync over every repository. [onProgress] reports units of
         * work done, which is the honest measure here -- the compare endpoint
         * does not carry file sizes, so byte-level progress would be a guess.
         *
         * A repository that fails does not stop the others. With one
         * repository that is the behaviour it always had; with several,
         * letting an unreachable one block the rest would make adding a second
         * repository a liability.
         */
        suspend fun sync(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SyncPlan =
            coroutineScope {
                val startedAt = System.currentTimeMillis()
                ensureSeeded()
                val targets = vaults.all().filter { it.enabled }
                if (targets.isEmpty()) throw NotConfiguredException("no repository configured")

                val plans = mutableListOf<SyncPlan>()
                var failure: Exception? = null
                var done = 0
                var total = 0

                targets.forEach { vault ->
                    try {
                        val plan =
                            syncOne(this, vault) { vaultDone, vaultTotal ->
                                // The total grows as each repository reports
                                // its own, which is honest: nothing knows the
                                // whole figure until the last one has planned.
                                onProgress(done + vaultDone, total + vaultTotal)
                            }
                        plans += plan
                        done += plan.downloads.size
                        total += plan.downloads.size
                    } catch (thrown: Exception) {
                        log.error("${vault.owner}/${vault.repo}: " + thrown.describeChain())
                        vaults.update(
                            vault.copy(lastError = thrown.message ?: thrown::class.simpleName),
                        )
                        if (failure == null) failure = thrown
                    }
                }

                // The journal's own row, which is what the UI and the icon
                // store watch. Per-repository state lives on the vault rows.
                syncState.upsert(
                    SyncStateEntity(
                        headCommit = plans.firstOrNull()?.headCommit?.takeIf { it.isNotEmpty() },
                        etagRef = null,
                        lastSyncAt = System.currentTimeMillis(),
                        lastError = failure?.let { it.message ?: it::class.simpleName },
                    ),
                )

                failure?.let { throw it }
                log.info("sync finished in ${(System.currentTimeMillis() - startedAt) / 1000}s")
                plans.merged()
            }

        private suspend fun syncOne(
            scope: CoroutineScope,
            vault: VaultEntity,
            onProgress: (done: Int, total: Int) -> Unit,
        ): SyncPlan {
            val where = if (vault.mount.isEmpty()) "the root" else vault.mount
            log.info("syncing ${vault.owner}/${vault.repo} into $where over ${vault.transport}")
            val transport = transportFor(vault)

            // A manifest built by an older filter is missing whatever the
            // filter has since started accepting, and no incremental refresh
            // will ever mention those files -- they did not change, the rule
            // did. Forgetting the commit for this one run plans from the full
            // tree instead, which still diffs against the manifest, so it adds
            // only what is absent rather than re-fetching the repository.
            val staleFilter = vault.filterVersion != VaultFilter.VERSION
            // Same reasoning one layer up: the notes are unchanged, what the
            // indexer makes of them is not.
            val staleIndex = vault.indexVersion != VaultIndexer.VERSION

            val base =
                SyncBase(
                    commit = vault.headCommit?.takeUnless { staleFilter },
                    // Handed to the transport unmounted, because a transport
                    // only ever speaks in repository-relative paths.
                    manifest =
                        blobs
                            .manifestRows(vault.id)
                            .associate { vault.unmounted(it.path) to it.sha },
                    etagRef = vault.etagRef?.takeUnless { staleFilter },
                )

            log.info("at ${vault.headCommit?.take(SHORT_SHA) ?: "nothing yet"}, ${base.manifest.size} files tracked")
            if (staleFilter) {
                log.info("what counts as vault content changed - reading the full tree once to catch up")
            }

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
                            "(${plan.unchanged} unchanged) -> ${plan.headCommit.take(SHORT_SHA)}",
                    )
                }
                if (!plan.isEmpty) {
                    val sink = MountedSink(vault, sinkProvider.get())
                    sink.plannedEntries = plan.downloads.associateBy { it.path }
                    transport.apply(plan, sink, onProgress)
                    log.info("downloaded ${plan.downloads.size} files")

                    val indexStart = System.currentTimeMillis()
                    index(vault, plan, firstSync = vault.headCommit == null)
                    log.info("indexed in ${(System.currentTimeMillis() - indexStart) / 1000}s")
                }
                if (staleIndex) {
                    log.info("the indexer derives more than it used to - rebuilding from what is on disk")
                    reindex()
                }
                vaults.update(
                    vault.copy(
                        headCommit = plan.headCommit.takeIf { it.isNotEmpty() } ?: vault.headCommit,
                        etagRef = plan.etagRef,
                        lastSyncAt = System.currentTimeMillis(),
                        lastError = null,
                        filterVersion = VaultFilter.VERSION,
                        indexVersion = VaultIndexer.VERSION,
                    ),
                )
                return plan
            } finally {
                narrator?.cancel()
            }
        }

        /**
         * Parses what just arrived into the searchable index.
         *
         * Never `indexAll` here, however first a sync is: that clears the
         * whole notes table, and with a second repository present it would
         * take the first one's notes with it. Upserting by path reaches the
         * same result for the repository being synced and leaves the rest
         * alone.
         */
        private suspend fun index(
            vault: VaultEntity,
            plan: SyncPlan,
            firstSync: Boolean,
        ) {
            val markdown =
                blobs
                    .byVaultKindAndState(vault.id, BlobKind.MARKDOWN, LocalState.DOWNLOADED)
                    .map { PathAndSha(it.path, it.sha) }

            if (firstSync) {
                indexer.indexChanged(changed = markdown, removed = emptyList())
                return
            }

            val touched =
                (plan.adds + plan.modifies).map { vault.mounted(it.path) }.toSet() +
                    plan.renames.map { vault.mounted(it.to) }.toSet()
            indexer.indexChanged(
                changed = markdown.filter { it.path in touched },
                removed =
                    plan.deletes.map { vault.mounted(it) } +
                        plan.renames.map { vault.mounted(it.from) },
            )
        }

        /**
         * Reparses everything already on disk, without touching the network.
         *
         * A sync only reindexes what changed, so anything derived during
         * indexing -- titles, headings, links, tasks, the search index --
         * stays as it was until the file itself does. This is the way to
         * rebuild it, and it spans every repository because links and search
         * do.
         */
        suspend fun reindex() {
            log.info("reindexing everything on disk")
            val started = System.currentTimeMillis()
            val markdown =
                blobs
                    .byKindAndState(BlobKind.MARKDOWN, LocalState.DOWNLOADED)
                    .map { PathAndSha(it.path, it.sha) }
            indexer.indexAll(markdown)
            log.info("reindexed ${markdown.size} notes in ${(System.currentTimeMillis() - started) / 1000}s")
        }

        /** Forgets everything so the next sync starts from nothing. */
        suspend fun reset() {
            blobs.clear()
            syncState.clear()
            vaults.all().forEach {
                vaults.update(
                    it.copy(headCommit = null, etagRef = null, lastSyncAt = null, lastError = null),
                )
            }
            log.warn("vault reset - the next sync starts from nothing")
        }

        /**
         * The chosen transport, built fresh each sync so a settings change
         * takes effect on the next refresh rather than on the next launch.
         */
        private suspend fun transportFor(vault: VaultEntity): VaultSync =
            when (SyncTransport.parse(vault.transport)) {
                SyncTransport.REST -> {
                    val client = client(vault)
                    RestVaultSync(
                        client = client,
                        branch = vault.branch ?: client.repository().defaultBranch,
                        filter = VaultFilter(),
                        log = log::info,
                    )
                }

                SyncTransport.SSH -> {
                    if (!sshKeys.exists(vault.mount)) {
                        throw NotConfiguredException(
                            "no SSH key for ${vault.label} yet - generate one in Settings and add it " +
                                "as a deploy key on that repository",
                        )
                    }
                    GitSshVaultSync(
                        // Each repository gets its own working tree, which is
                        // what lets one key serve all of them without their
                        // histories colliding.
                        workTree = files.fileFor(vault.mount),
                        remoteUrl = sshUrl(vault),
                        branch = vault.branch ?: DEFAULT_BRANCH,
                        keys = sshKeys,
                        keyMount = vault.mount,
                        configDir = File(context.filesDir, "git"),
                        log = log::info,
                    )
                }
            }

        /**
         * GitHub answers SSH on 443 as well as 22, which is the way round a
         * network that blocks 22 -- otherwise the clone just hangs.
         */
        private suspend fun sshUrl(vault: VaultEntity): String =
            if (settings.current().sshOverPort443) {
                "ssh://git@ssh.github.com:443/" + vault.owner + "/" + vault.repo + ".git"
            } else {
                "git@github.com:" + vault.owner + "/" + vault.repo + ".git"
            }

        private suspend fun client(vault: VaultEntity): GitHubClient {
            val token = tokens.token() ?: throw NotConfiguredException("no access token stored")
            return GitHubClient(
                GitHubConfig(
                    owner = vault.owner,
                    repo = vault.repo,
                    branch = vault.branch,
                    token = token,
                ),
                http = http,
            )
        }

        /** One plan describing everything that happened, for the worker's report. */
        private fun List<SyncPlan>.merged(): SyncPlan =
            SyncPlan(
                baseCommit = firstOrNull()?.baseCommit,
                headCommit = firstOrNull()?.headCommit.orEmpty(),
                adds = flatMap { it.adds },
                modifies = flatMap { it.modifies },
                renames = flatMap { it.renames },
                deletes = flatMap { it.deletes },
                unchanged = sumOf { it.unchanged },
            )

        private companion object {
            const val DEFAULT_BRANCH = "main"
            const val SHORT_SHA = 7
        }
    }
