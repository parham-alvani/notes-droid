package me.parham1995.notes.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.PendingEditDao
import me.parham1995.notes.data.database.SyncStateDao
import me.parham1995.notes.data.database.SyncStateEntity
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.GitSshVaultSync
import me.parham1995.notes.data.git.SshKeyStore
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.RepositoryInfo
import me.parham1995.notes.sync.SyncBase
import me.parham1995.notes.sync.SyncPlan
import me.parham1995.notes.sync.VaultFilter
import me.parham1995.notes.sync.VaultSync
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
        private val notes: NoteDao,
        private val syncState: SyncStateDao,
        private val vaults: VaultDao,
        private val sinkProvider: Provider<RoomVaultSink>,
        private val indexer: VaultIndexer,
        private val log: SyncLog,
        private val files: VaultFileStore,
        private val sshKeys: SshKeyStore,
        private val pending: PendingEditDao,
        private val transports: VaultTransports,
        private val writes: VaultWriteRepository,
        private val gate: VaultGate,
    ) {
        val status: Flow<SyncStatus> =
            syncState.observe().map {
                SyncStatus(it?.headCommit, it?.lastSyncAt, it?.lastError)
            }

        /**
         * Markdown files in the vault being read -- the same one the browser
         * and search answer for, chosen the same way.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val noteCount: Flow<Int> =
            combine(settings.settings, vaults.observe()) { current, all ->
                all.firstOrNull { it.id == current.activeVaultId }?.id ?: all.firstOrNull()?.id
            }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(0) else blobs.countOfKind(id, BlobKind.MARKDOWN) }

        /**
         * Every repository this app reads, in the order they are shown.
         *
         * Keys are moved to their id-based names on the way past, because
         * this is what every screen that shows a key is built from -- and it
         * has to have happened before one asks whether a vault has a key.
         */
        fun vaults(): Flow<List<VaultEntity>> =
            vaults
                .observe()
                .onEach { sshKeys.adoptLegacyNames(it) }
                .flowOn(Dispatchers.IO)

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
                        name = current.repo,
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
            name: String,
            transport: SyncTransport,
        ): Long {
            val existing = vaults.all()
            return vaults.insert(
                VaultEntity(
                    owner = owner.trim(),
                    repo = repo.trim(),
                    branch = branch?.trim()?.takeIf { it.isNotBlank() },
                    name = name.trim().ifBlank { repo.trim() },
                    transport = transport.name,
                    ordinal = existing.size,
                ),
            )
        }

        suspend fun updateVault(vault: VaultEntity) = vaults.update(vault)

        /**
         * Moves a vault to another transport.
         *
         * The commit and ETag go with the change. Each transport's idea of
         * "where the device is up to" is its own: an SSH clone is shallow and
         * does not hold the commit a REST sync recorded, and a REST sync
         * replaying an SSH-era ETag can be told nothing moved when the files
         * on disk were never its own. Planning the next sync from the full
         * tree still diffs against the manifest, so nothing already here is
         * fetched again. Whether the credential can write is a question for
         * the new credential, so that goes too until it is asked.
         *
         * Under the gate, and read fresh: a sync that is running holds the
         * row it started with and writes it back at the end, which would put
         * the old transport back.
         */
        suspend fun setTransport(
            vaultId: Long,
            transport: SyncTransport,
        ) = gate.withVault {
            val vault = vaults.byId(vaultId) ?: return@withVault
            if (SyncTransport.parse(vault.transport) == transport) return@withVault
            vaults.update(
                vault.copy(transport = transport.name, headCommit = null, etagRef = null, canWrite = false),
            )
            log.info("${vault.label} now syncs over ${transport.name} - the next sync reads the whole tree once")
        }

        /**
         * Forgets a repository and everything it brought with it.
         *
         * The notes are removed through the indexer rather than by deleting
         * rows, so the search index and the links pointing at them go too --
         * a note deleted from `notes` alone leaves an FTS row that still
         * matches and a backlink that still resolves.
         */
        suspend fun removeVault(id: Long) =
            gate.withVault {
                removeVaultLocked(id)
            }

        /**
         * Under the gate, like everything else here that touches a vault's
         * files or rows. Deleting a working tree while a sync checks it out,
         * or while a write commits from it, is the race the gate exists for.
         */
        private suspend fun removeVaultLocked(id: Long) {
            val vault = vaults.byId(id) ?: return
            // Through the indexer rather than by deleting rows: a note removed
            // from `notes` alone leaves a search entry that still matches and a
            // backlink that still resolves.
            // Every note the vault has in the index, not every markdown file
            // its manifest calls downloaded: a note indexed from a row that
            // has since changed state -- or from no row at all -- was left
            // behind with its tasks, links and search entry, showing up in
            // task lists for a vault that no longer existed.
            val owned = notes.allIds(id).map { it.path }
            indexer.indexChanged(vaultId = id, changed = emptyList(), removed = owned)
            blobs.clearVault(id)
            // Its queued edits go too. A flush would eventually notice the
            // vault was gone and drop them, but until then they sit in the
            // queue looking like work still to do.
            pending.clearVault(id)
            files.deleteVault(id)
            sshKeys.delete(vault.id)
            vaults.delete(id)
            log.warn("removed ${vault.owner}/${vault.repo}")
        }

        /**
         * Authenticates one repository's SSH key against its host and reports
         * what happened, without transferring anything.
         *
         * This is the check that was missing all along. A key that was never
         * registered and a key the host refused fail identically once a clone
         * is under way, and telling them apart by reading a transfer error is
         * how a working deploy key was thrown away and replaced. Twenty
         * seconds and a handshake answers it outright.
         */
        suspend fun testSshKey(vault: VaultEntity): Result<String> =
            // Gated: it opens the same working tree a sync drives, and records
            // what it learned on the vault row a sync writes back at the end.
            gate.withVault { testSshKeyLocked(vault) }

        private suspend fun testSshKeyLocked(vault: VaultEntity): Result<String> =
            runCatchingUnlessCancelled {
                transports.adoptLegacyKeys()
                if (!sshKeys.exists(vault.id)) {
                    error("no key for ${vault.label} yet")
                }
                val transport = transports.ssh(vault)
                transport.authenticate()
                val write = if (transport.canPush()) "read and write" else "read only"
                vaults.update(vaults.byId(vault.id)!!.copy(canWrite = write.startsWith("read and")))
                "authenticated against ${vault.owner}/${vault.repo} ($write)"
            }

        /**
         * Re-asks every repository whether its credential may write, and says
         * what the answers were.
         *
         * Offered as a button because the answer changes outside the app -- a
         * deploy key gets write ticked, a token is replaced -- and waiting for
         * the next background sync to notice is a long time to stare at a
         * checkbox that is not there.
         */
        suspend fun refreshWriteAccess(): String =
            // A sync writes back the row it started with; an answer recorded
            // underneath it would be overwritten with the old one.
            gate.withVault { refreshWriteAccessLocked() }

        private suspend fun refreshWriteAccessLocked(): String {
            val targets = vaults.all().filter { it.enabled }
            if (targets.isEmpty()) throw NotConfiguredException("no repository configured")
            return targets
                .map { vault ->
                    refreshWritability(vault)
                    val can = vaults.byId(vault.id)?.canWrite == true
                    "${vault.label}: " + if (can) "can write" else "read-only"
                }.joinToString("\n")
        }

        /** Confirms the token and repository before anything is synced. */
        suspend fun testConnection(): RepositoryInfo {
            ensureSeeded()
            val vault =
                vaults.all().firstOrNull { it.enabled }
                    ?: throw NotConfiguredException("no repository configured")
            val info = transports.client(vault).repository()
            vaults.update(vault.copy(canWrite = info.canPush))
            return info
        }

        /**
         * Moves each vault's files into the directory named by its id.
         *
         * Vaults used to live in a directory named after the folder they were
         * mounted at, and the first one lived in the shared root with the
         * others beside it. Naming by id instead means renaming a vault costs
         * nothing, but the files already on the device are in the old places.
         *
         * Renaming beats re-fetching by a wide margin here -- the two
         * repositories this was written against are a hundred and two hundred
         * megabytes -- and it keeps the git working trees intact, `.git` and
         * all, so an SSH vault does not re-clone.
         *
         * A vault whose files cannot be moved has its manifest cleared instead,
         * which costs a re-sync rather than leaving rows pointing at files that
         * are no longer there.
         */
        private suspend fun migrateStorage() {
            val all = vaults.all()
            if (all.isEmpty()) return

            // Named vaults first, so that whatever is left in the shared root
            // afterwards belongs to the one that used to live there.
            all.filter { !files.rootOf(it.id).exists() }.forEach { vault ->
                val old = File(files.root, vault.name)
                if (old.isDirectory) {
                    moveOrReset(vault, from = { old.renameTo(files.rootOf(vault.id)) })
                }
            }

            val stragglers = all.filter { !files.rootOf(it.id).exists() }
            if (stragglers.size != 1) {
                // Either nothing is left to move, or more than one vault claims
                // the root, which cannot be resolved by guessing.
                stragglers.forEach { blobs.clearVault(it.id) }
                return
            }

            val vault = stragglers.single()
            val target = files.rootOf(vault.id)
            moveOrReset(vault) {
                target.mkdirs()
                files.root
                    .listFiles()
                    .orEmpty()
                    .filterNot { it == target || it.name.toLongOrNull() != null }
                    .all { it.renameTo(File(target, it.name)) }
            }
        }

        private suspend fun moveOrReset(
            vault: VaultEntity,
            from: () -> Boolean,
        ) {
            val moved = runCatching { from() }.getOrDefault(false)
            if (moved) {
                log.info("moved ${vault.label} into its own directory")
            } else {
                log.warn("could not move ${vault.label}; it will be fetched again")
                blobs.clearVault(vault.id)
            }
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
            // Held for the whole sync. A write that arrives while this is
            // running drives the same working tree, and the two interleaved
            // produce a commit of a half-checked-out tree or an edit lost in
            // the checkout.
            gate.withVault {
                syncLocked(onProgress)
            }

        private suspend fun syncLocked(onProgress: (done: Int, total: Int) -> Unit): SyncPlan =
            coroutineScope {
                val startedAt = System.currentTimeMillis()
                ensureSeeded()
                migrateStorage()
                // Before anything is pulled: an SSH vault's working tree is
                // reset during a sync, which would take an edit's local copy
                // with it and make a queued change look like a lost one. The
                // unlocked form, because the gate is already held here.
                runCatchingUnlessCancelled { writes.drain() }
                    .onFailure { log.warn("could not send queued edits: " + it.describeChain()) }
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
                    } catch (cancelled: CancellationException) {
                        // Not a failure of this vault: the whole sync was
                        // stopped, and the next vault must not start.
                        throw cancelled
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

                // Again at the end: an edit made *during* this sync could not
                // take the gate and queued instead, and the flush at the start
                // is long past. Without this it would wait for the next one.
                runCatchingUnlessCancelled { writes.drain() }
                    .onFailure { log.warn("could not send queued edits: " + it.describeChain()) }

                failure?.let { throw it }
                log.info("sync finished in ${(System.currentTimeMillis() - startedAt) / 1000}s")
                plans.merged()
            }

        private suspend fun syncOne(
            scope: CoroutineScope,
            vault: VaultEntity,
            onProgress: (done: Int, total: Int) -> Unit,
        ): SyncPlan {
            log.info("syncing ${vault.owner}/${vault.repo} into ${vault.label} over ${vault.transport}")
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
                    manifest = blobs.manifestRows(vault.id).associate { it.path to it.sha },
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
                    val sink = sinkProvider.get()
                    sink.vaultId = vault.id
                    sink.plannedEntries = plan.downloads.associateBy { it.path }
                    transport.apply(plan, sink, onProgress)
                    log.info("downloaded ${plan.downloads.size} files")

                    val indexStart = System.currentTimeMillis()
                    index(vault, plan, firstSync = vault.headCommit == null)
                    log.info("indexed in ${(System.currentTimeMillis() - indexStart) / 1000}s")
                }
                catchUpIndex(vault)
                if (staleIndex) {
                    log.info("the indexer derives more than it used to - rebuilding from what is on disk")
                    // This vault only. Each vault carries its own index
                    // version and is brought up to date when it syncs; the
                    // whole-app rebuild here ran once per stale vault, so
                    // three vaults after an upgrade were each rebuilt three
                    // times over.
                    reindexVault(vault)
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
                refreshWritability(vault)
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
                indexer.indexChanged(vaultId = vault.id, changed = markdown, removed = emptyList())
                return
            }

            // Paths are already relative to the vault, on both sides, so
            // nothing has to be translated between the plan and the index.
            val touched =
                (plan.adds + plan.modifies).map { it.path }.toSet() +
                    plan.renames.map { it.to }.toSet()
            indexer.indexChanged(
                vaultId = vault.id,
                changed = markdown.filter { it.path in touched },
                removed = plan.deletes + plan.renames.map { it.from },
            )
        }

        /**
         * Indexes markdown whose note lags the manifest.
         *
         * A sync that stopped between downloading and indexing -- killed,
         * cancelled, a parse that threw -- leaves the manifest at the new sha
         * and the note at the old one. The next plan diffs against the
         * manifest, so it never mentions those files again, and without this
         * the note would read as its old self until the file changed once
         * more. One query when nothing is behind, which is nearly always.
         */
        private suspend fun catchUpIndex(vault: VaultEntity) {
            val behind =
                blobs
                    .indexBehind(vault.id, BlobKind.MARKDOWN, LocalState.DOWNLOADED)
                    .map { PathAndSha(it.path, it.sha) }
                    // Guides are never notes, so they are always "behind".
                    .filterNot { VaultIndexer.isGuide(it.path) }
            if (behind.isEmpty()) return
            log.info("${behind.size} notes were downloaded but not indexed - indexing them now")
            indexer.indexChanged(vaultId = vault.id, changed = behind, removed = emptyList())
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
        suspend fun reindex() =
            // Clearing a vault's notes while a sync is adding to them leaves
            // whichever finished second holding half the answer.
            gate.withVault { reindexLocked() }

        private suspend fun reindexLocked() {
            log.info("reindexing everything on disk")
            val started = System.currentTimeMillis()
            var total = 0
            vaults.all().forEach { vault -> total += reindexVault(vault) }
            log.info("reindexed $total notes in ${(System.currentTimeMillis() - started) / 1000}s")
        }

        /** Reparses one vault from disk, and says how many notes that was. */
        private suspend fun reindexVault(vault: VaultEntity): Int {
            val theirs =
                blobs
                    .byVaultKindAndState(vault.id, BlobKind.MARKDOWN, LocalState.DOWNLOADED)
                    .map { PathAndSha(it.path, it.sha) }
            // Named in the journal per vault, because "reindexed 2,400
            // notes" says nothing about which vault came out empty.
            log.info("reindexing ${theirs.size} notes in ${vault.label}")
            indexer.indexAll(vault.id, theirs)
            return theirs.size
        }

        /** Forgets everything so the next sync starts from nothing. */
        suspend fun reset() =
            // A sync in flight would write rows back into the manifest this
            // just emptied, and the next one would trust them.
            gate.withVault { resetLocked() }

        private suspend fun resetLocked() {
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
        private suspend fun transportFor(vault: VaultEntity): VaultSync = transports.reader(vault)

        /**
         * Asks the host what this vault's credential may do, and records it.
         *
         * Done on every sync rather than once, because the answer changes
         * outside the app: a deploy key gets write ticked, a token is replaced
         * with a narrower one. Never inferred from a failure -- a write
         * offered on a read-only credential fails after the person has already
         * typed the thing they wanted to save.
         */
        private suspend fun refreshWritability(vault: VaultEntity) {
            val can =
                runCatchingUnlessCancelled { transports.writer(vault).canPush() }
                    .onFailure { log.warn("could not check write access for ${vault.label}: ${it.describeChain()}") }
                    .getOrDefault(false)
            if (can != vault.canWrite) {
                log.info("${vault.label} is now " + if (can) "writable" else "read-only")
            }
            vaults.byId(vault.id)?.let { vaults.update(it.copy(canWrite = can)) }
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
