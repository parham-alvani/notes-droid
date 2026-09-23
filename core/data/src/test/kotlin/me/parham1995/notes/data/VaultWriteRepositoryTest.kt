package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.sync.Author
import me.parham1995.notes.sync.TextEdit
import me.parham1995.notes.sync.VaultWriter
import me.parham1995.notes.sync.WriteOutcome
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Writing, without a network.
 *
 * What is worth testing here is not that a commit happens -- that is the
 * transport's job -- but everything around it: that nothing is offered on a
 * credential that cannot push, that an edit made with no signal is kept and
 * re-applied rather than replayed, and that re-applying it lands in the file as
 * it is *then*, not as it was when the edit was made.
 */
@RunWith(RobolectricTestRunner::class)
class VaultWriteRepositoryTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var settings: SettingsStore
    private lateinit var writes: VaultWriteRepository
    private lateinit var transport: FakeWriter
    private lateinit var indexer: VaultIndexer
    private lateinit var build: (VaultGate) -> VaultWriteRepository

    private val vaultId = 1L

    /** A writer that keeps the file in memory and can be told to fail. */
    private class FakeWriter : VaultWriter {
        var content: String? = null
        var offline = false
        var writable = true
        val messages = mutableListOf<String>()

        override suspend fun canPush(): Boolean = writable

        override suspend fun write(
            path: String,
            message: String,
            author: Author,
            edit: TextEdit,
        ): WriteOutcome {
            if (offline) throw IOException("no route to host")
            val updated = edit.applyTo(content) ?: return WriteOutcome.NotApplicable
            content = updated
            messages += message
            return WriteOutcome.Written("commit${messages.size}", updated)
        }
    }

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            database = testDatabase()
            files = VaultFileStore(context)
            settings = SettingsStore(context)
            transport = FakeWriter()

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
                    sshKeys =
                        me.parham1995.notes.data.git
                            .SshKeyStore(context),
                    log = SyncLog(database.syncLogDao()),
                    context = context,
                    http = OkHttpClient(),
                    vaults = database.vaultDao(),
                ) {
                    override suspend fun writer(vault: VaultEntity) = transport
                }

            database.vaultDao().insert(
                VaultEntity(id = vaultId, owner = "someone", repo = "vault", name = "vault", canWrite = true),
            )
            settings.setAuthor("A Person", "person@example.com")

            build = { gate ->
                VaultWriteRepository(
                    settings = settings,
                    vaults = database.vaultDao(),
                    pending = database.pendingEditDao(),
                    blobs = database.blobDao(),
                    files = files,
                    indexer = indexer,
                    transports = transports,
                    log = SyncLog(database.syncLogDao()),
                    gate = gate,
                )
            }
            writes = build(VaultGate())
        }

    /** The same repository, on a gate the test can hold itself. */
    private fun repositoryOn(gate: VaultGate) = build(gate)

    @After
    fun tearDown() =
        runTest {
            database.close()
            files.clear()
        }

    @Test
    fun `a task added shows up in the task list without a reindex`() =
        runTest {
            val path = "Tasks/Work.md"
            files.write(vaultId, path, "## Alpha\n\n- [ ] first\n".toByteArray())
            indexer.indexAll(vaultId, listOf(PathAndSha(path, "sha-seed")))
            transport.content = files.readText(vaultId, path)
            assertThat(database.taskDao().open(vaultId).first()).hasSize(1)

            val result = writes.addTask(vaultId, path, "Alpha", "second")

            assertThat(result).isEqualTo(WriteResult.Pushed)
            assertThat(files.readText(vaultId, path)).contains("- [ ] second")
            // The point of the test: a write indexes what it wrote, so the
            // task list answers immediately rather than after a reindex.
            assertThat(
                database
                    .taskDao()
                    .open(vaultId)
                    .first()
                    .map { it.text },
            ).containsExactly("first", "second")
            // The line each task sits on, because that is what a completion
            // looks it up by and it moves whenever the note above it does.
            val noteId = database.noteDao().byPath(vaultId, path)!!.id
            assertThat(database.taskDao().byNote(noteId).map { it.line }).containsExactly(2, 3)
        }

    @Test
    fun `a write during a refresh is queued rather than blocked`() =
        runTest {
            val gate = VaultGate()
            val writes = repositoryOn(gate)

            // A sync holds the vault. The write must not wait for it.
            val result = gate.withVault { writes.capture("caught mid-refresh") }

            assertThat(result).isInstanceOf(WriteResult.Queued::class.java)
            assertThat(database.pendingEditDao().all()).hasSize(1)
            // Nothing was written to disk: a checkout is in progress under it.
            assertThat(files.readText(vaultId, SCRATCHPAD)).isNull()

            // And it goes as soon as the vault is free again.
            assertThat(writes.flush()).isEqualTo(1)
            assertThat(transport.content).contains("caught mid-refresh")
        }

    @Test
    fun `completing a repeat leaves the next occurrence behind it`() =
        runTest {
            val path = "Tasks/Work.md"
            val today = java.time.LocalDate.now()
            val due = today.plusDays(1)
            val line = "- [ ] bins 🔁 every day ⏳ $due"
            files.write(vaultId, path, "## Alpha\n\n$line\n".toByteArray())
            indexer.indexAll(vaultId, listOf(PathAndSha(path, "sha-seed")))
            transport.content = files.readText(vaultId, path)
            val task =
                database
                    .taskDao()
                    .open(vaultId)
                    .first()
                    .single()

            assertThat(writes.completeTask(task)).isEqualTo(WriteResult.Pushed)

            val after = files.readText(vaultId, path).orEmpty()
            assertThat(after).contains("- [ ] bins 🔁 every day ⏳ ${due.plusDays(1)}")
            assertThat(after).contains("- [x] bins 🔁 every day ⏳ $due ✅ $today")
            // And the list still has exactly one thing to do.
            assertThat(
                database
                    .taskDao()
                    .open(vaultId)
                    .first()
                    .map { it.text },
            ).containsExactly("bins")
        }

    @Test
    fun `a read-only vault is refused, and nothing is queued`() =
        runTest {
            database.vaultDao().update(database.vaultDao().byId(vaultId)!!.copy(canWrite = false))

            val result = writes.capture("a thought")

            assertThat(result).isInstanceOf(WriteResult.Refused::class.java)
            assertThat(database.pendingEditDao().all()).isEmpty()
        }

    @Test
    fun `no author is refused even where the credential can push`() =
        runTest {
            settings.setAuthor("", "")

            assertThat(writes.capture("a thought")).isInstanceOf(WriteResult.Refused::class.java)
            assertThat(database.pendingEditDao().all()).isEmpty()
        }

    @Test
    fun `a capture reaches the repository and the device together`() =
        runTest {
            val result = writes.capture("a thought")

            assertThat(result).isEqualTo(WriteResult.Pushed)
            assertThat(transport.content).contains("a thought")
            assertThat(files.readText(vaultId, SCRATCHPAD)).isEqualTo(transport.content)
            assertThat(database.pendingEditDao().all()).isEmpty()
        }

    @Test
    fun `an edit made offline is kept, and applies to the file as it is when it goes up`() =
        runTest {
            transport.offline = true

            val queued = writes.capture("caught on a train")

            assertThat(queued).isInstanceOf(WriteResult.Queued::class.java)
            assertThat(database.pendingEditDao().all()).hasSize(1)
            // Written locally regardless, so the app tells the truth about what
            // it was asked to do.
            assertThat(files.readText(vaultId, SCRATCHPAD)).contains("caught on a train")

            // Meanwhile the same file grew a line at the desk.
            transport.offline = false
            transport.content = "## 2026-01-01\n\n- written at the desk\n"

            assertThat(writes.flush()).isEqualTo(1)
            // Both survive: the edit was re-applied to the new content rather
            // than replayed over it.
            assertThat(transport.content).contains("written at the desk")
            assertThat(transport.content).contains("caught on a train")
            assertThat(database.pendingEditDao().all()).isEmpty()
        }

    @Test
    fun `a scheduled flush gives up on an edit that keeps failing`() =
        runTest {
            transport.offline = true
            writes.capture("never lands")

            // Five scheduled attempts, as a sync would make them.
            repeat(6) { writes.drain() }
            val edit = database.pendingEditDao().all().single()
            assertThat(edit.attempts).isEqualTo(5)

            // Still there, and still sendable by hand -- a person asking for it
            // always gets one more try.
            transport.offline = false
            assertThat(writes.flush()).isEqualTo(1)
            assertThat(database.pendingEditDao().all()).isEmpty()
        }

    @Test
    fun `an edit whose file already says it leaves the queue quietly`() =
        runTest {
            transport.offline = true
            writes.capture("say it once")
            transport.offline = false
            // Someone wrote the very same line at the desk first.
            transport.content = files.readText(vaultId, SCRATCHPAD)

            assertThat(writes.flush()).isEqualTo(1)
            assertThat(database.pendingEditDao().all()).isEmpty()
        }

    private companion object {
        const val SCRATCHPAD = "Scratchpad.md"
    }
}
