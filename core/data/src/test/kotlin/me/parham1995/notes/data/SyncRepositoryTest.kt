package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.BlobEntity
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.SshKeyStore
import me.parham1995.notes.sync.Author
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.SyncBase
import me.parham1995.notes.sync.SyncPlan
import me.parham1995.notes.sync.VaultFilter
import me.parham1995.notes.sync.VaultSink
import me.parham1995.notes.sync.VaultSync
import me.parham1995.notes.sync.TextEdit
import me.parham1995.notes.sync.VaultWriter
import me.parham1995.notes.sync.WriteOutcome
import me.parham1995.notes.sync.gitBlobSha
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sync as a whole, with the network stood in for.
 *
 * The transports have their own tests. What is tested here is what happens
 * around them: which vault a step is about, what is left behind when a sync
 * stops part way, and what the gate keeps apart.
 */
@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var settings: SettingsStore
    private lateinit var indexer: VaultIndexer
    private lateinit var gate: VaultGate
    private lateinit var repository: SyncRepository

    /** A transport that has nothing new to say, unless told otherwise. */
    private inner class FakeSync : VaultSync {
        var plans = 0

        override suspend fun plan(base: SyncBase): SyncPlan {
            plans++
            return SyncPlan(base.commit, base.commit ?: "head")
        }

        override suspend fun apply(
            plan: SyncPlan,
            sink: VaultSink,
            onProgress: (done: Int, total: Int) -> Unit,
        ) = Unit
    }

    private val readers = mutableMapOf<Long, FakeSync>()

    private fun readerFor(vaultId: Long) = readers.getOrPut(vaultId) { FakeSync() }

    private object ReadOnly : VaultWriter {
        override suspend fun canPush(): Boolean = false

        override suspend fun write(
            path: String,
            message: String,
            author: Author,
            edit: TextEdit,
        ): WriteOutcome = WriteOutcome.NotApplicable
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = testDatabase()
        files = VaultFileStore(context)
        settings = SettingsStore(context)
        gate = VaultGate()
        val log = SyncLog(database.syncLogDao())
        val keys = SshKeyStore(context)
        indexer =
            VaultIndexer(
                files = files,
                notes = database.noteDao(),
                links = database.linkDao(),
                headings = database.headingDao(),
                tasks = database.taskDao(),
                index = database.indexDao(),
                search = SearchIndex(database),
            )
        val transports =
            object : VaultTransports(
                settings = settings,
                tokens = TokenStore(context),
                files = files,
                sshKeys = keys,
                log = log,
                context = context,
                http = OkHttpClient(),
                vaults = database.vaultDao(),
            ) {
                override suspend fun writer(vault: VaultEntity): VaultWriter = ReadOnly

                override suspend fun reader(vault: VaultEntity): VaultSync = readerFor(vault.id)
            }
        val writes =
            VaultWriteRepository(
                settings = settings,
                vaults = database.vaultDao(),
                pending = database.pendingEditDao(),
                blobs = database.blobDao(),
                files = files,
                indexer = indexer,
                transports = transports,
                log = log,
                gate = gate,
            )
        repository =
            SyncRepository(
                settings = settings,
                tokens = TokenStore(context),
                blobs = database.blobDao(),
                syncState = database.syncStateDao(),
                vaults = database.vaultDao(),
                sinkProvider = { RoomVaultSink(files, database.blobDao()) },
                indexer = indexer,
                log = log,
                files = files,
                sshKeys = keys,
                pending = database.pendingEditDao(),
                transports = transports,
                writes = writes,
                gate = gate,
            )
    }

    @After
    fun tearDown() =
        runTest {
            database.close()
            files.clear()
        }

    /** A vault that has synced before, under the current filter and indexer. */
    private suspend fun syncedVault(name: String): Long =
        database.vaultDao().insert(
            VaultEntity(
                owner = "o",
                repo = name,
                name = name,
                headCommit = "head",
                filterVersion = VaultFilter.VERSION,
                indexVersion = VaultIndexer.VERSION,
            ),
        )

    private suspend fun onDisk(
        vaultId: Long,
        path: String,
        text: String,
    ): String {
        val bytes = text.toByteArray()
        val sha = gitBlobSha(bytes)
        files.write(vaultId, path, bytes)
        database.blobDao().upsert(
            BlobEntity(
                path = path,
                vaultId = vaultId,
                sha = sha,
                size = bytes.size.toLong(),
                kind = BlobKind.MARKDOWN,
                localState = LocalState.DOWNLOADED,
            ),
        )
        return sha
    }

    @Test
    fun `a note downloaded but never indexed is indexed by the next sync`() =
        runTest {
            val id = syncedVault("notes")
            val old = onDisk(id, "Plan.md", "# Plan\n\n- [ ] the old task\n")
            indexer.indexAll(id, listOf(PathAndSha("Plan.md", old)))

            // The sync that brought the new version was stopped after the
            // download and before the index: file and manifest are new, the
            // note is not. The next plan diffs against the manifest and says
            // nothing changed.
            val new = onDisk(id, "Plan.md", "# Plan\n\n- [ ] the new task\n")

            repository.sync()

            assertThat(database.noteDao().byPath(id, "Plan.md")!!.blobSha).isEqualTo(new)
            assertThat(database.taskDao().open(id).first().map { it.text }).containsExactly("the new task")
        }

    @Test
    fun `switching transport forgets where the old one was up to`() =
        runTest {
            val id =
                database.vaultDao().insert(
                    VaultEntity(
                        owner = "o",
                        repo = "r",
                        name = "notes",
                        transport = "REST",
                        headCommit = "rest-era-commit",
                        etagRef = "\"etag\"",
                        canWrite = true,
                    ),
                )

            repository.setTransport(id, SyncTransport.SSH)

            // The shallow clone never holds the commit REST recorded, and the
            // key is not the token: both have to be found out again.
            val vault = database.vaultDao().byId(id)!!
            assertThat(vault.transport).isEqualTo("SSH")
            assertThat(vault.headCommit).isNull()
            assertThat(vault.etagRef).isNull()
            assertThat(vault.canWrite).isFalse()
        }
}
