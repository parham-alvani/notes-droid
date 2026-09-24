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
import me.parham1995.notes.sync.gitBlobSha
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Updated since you read it", over the real indexer and database.
 *
 * The laptop commits the vault every few minutes, so the list is only worth
 * anything if it is exact: a note the person has never opened is not news, a
 * note they changed themselves from the phone is not news, and a note in the
 * other vault is not this vault's news. Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class UpdatedSinceReadTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var settings: SettingsStore
    private lateinit var indexer: VaultIndexer
    private lateinit var writes: VaultWriteRepository
    private val transport = MemoryWriter()

    /** A writer that keeps the one file it is asked about in memory. */
    private class MemoryWriter : VaultWriter {
        var content: String? = null

        override suspend fun canPush(): Boolean = true

        override suspend fun write(
            path: String,
            message: String,
            author: Author,
            edit: TextEdit,
        ): WriteOutcome {
            val updated = edit.applyTo(content) ?: return WriteOutcome.NotApplicable
            content = updated
            return WriteOutcome.Written("commit", updated)
        }
    }

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            database = testDatabase()
            files = VaultFileStore(context)
            settings = SettingsStore(context)
            indexer =
                VaultIndexer(
                    files = files,
                    notes = database.noteDao(),
                    links = database.linkDao(),
                    headings = database.headingDao(),
                    tasks = database.taskDao(),
                    index = database.indexDao(),
                    search = SearchIndex(database),
                    aliases = database.aliasDao(),
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
                VaultEntity(id = VAULT, owner = "someone", repo = "vault", name = "vault", canWrite = true),
            )
            database.vaultDao().insert(
                VaultEntity(id = OTHER, owner = "someone", repo = "other", name = "other", canWrite = true),
            )
            settings.setAuthor("A Person", "person@example.com")
            writes =
                VaultWriteRepository(
                    settings = settings,
                    vaults = database.vaultDao(),
                    pending = database.pendingEditDao(),
                    blobs = database.blobDao(),
                    files = files,
                    indexer = indexer,
                    transports = transports,
                    log = SyncLog(database.syncLogDao()),
                    gate = VaultGate(),
                )
        }

    @After
    fun tearDown() =
        runTest {
            database.close()
            files.clear()
        }

    /** Puts [text] on disk and indexes it the way a sync does, sha and all. */
    private suspend fun arrive(
        path: String,
        text: String,
        vault: Long = VAULT,
    ) {
        files.write(vault, path, text.toByteArray())
        indexer.indexChanged(
            vault,
            changed = listOf(PathAndSha(path, gitBlobSha(text.toByteArray()))),
            removed = emptyList(),
        )
    }

    private suspend fun open(
        path: String,
        vault: Long = VAULT,
        at: Long = 1_000L,
    ) {
        database.noteDao().markOpened(database.noteDao().idOf(vault, path)!!, at)
    }

    private suspend fun updated(vault: Long = VAULT): List<String> =
        database
            .noteDao()
            .updatedSinceRead(vault, 20)
            .first()
            .map { it.path }

    @Test
    fun `a note read and then changed upstream is listed, until it is read again`() =
        runTest {
            arrive("Journal/Today.md", "morning")
            open("Journal/Today.md")
            assertThat(updated()).isEmpty()

            arrive("Journal/Today.md", "morning\n\nafternoon")
            assertThat(updated()).containsExactly("Journal/Today.md")

            open("Journal/Today.md", at = 2_000L)
            assertThat(updated()).isEmpty()
        }

    @Test
    fun `a note never opened is not listed, however it changes`() =
        runTest {
            arrive("Inbox.md", "one")
            arrive("Inbox.md", "two")

            assertThat(updated()).isEmpty()
        }

    @Test
    fun `another vault's change is not this vault's news`() =
        runTest {
            // The same path in both, opened in both: only the path and the
            // vault tell them apart.
            arrive("README.md", "ours")
            arrive("README.md", "theirs", vault = OTHER)
            open("README.md")
            open("README.md", vault = OTHER)

            arrive("README.md", "theirs, changed", vault = OTHER)

            assertThat(updated()).isEmpty()
            assertThat(updated(OTHER)).containsExactly("README.md")
        }

    @Test
    fun `the most recent change comes first`() =
        runTest {
            arrive("A.md", "a")
            arrive("B.md", "b")
            open("A.md")
            open("B.md")

            arrive("B.md", "b changed")
            Thread.sleep(5)
            arrive("A.md", "a changed")

            assertThat(updated()).containsExactly("A.md", "B.md").inOrder()
        }

    @Test
    fun `reindexing keeps what was read and when it last changed`() =
        runTest {
            // The indexer builds a fresh entity from the file, knowing
            // neither; carried over like openedAt, or a full reindex would
            // light up every note or bury every change.
            arrive("Note.md", "before")
            open("Note.md")
            arrive("Note.md", "after")
            val changed = database.noteDao().byPath(VAULT, "Note.md")!!.changedAt

            Thread.sleep(5)
            indexer.indexAll(VAULT, listOf(PathAndSha("Note.md", gitBlobSha("after".toByteArray()))))

            val note = database.noteDao().byPath(VAULT, "Note.md")!!
            assertThat(note.changedAt).isEqualTo(changed)
            assertThat(note.readSha).isEqualTo(gitBlobSha("before".toByteArray()))
            assertThat(updated()).containsExactly("Note.md")
        }

    @Test
    fun `what the phone wrote itself is not news`() =
        runTest {
            val path = "Tasks/Work.md"
            arrive(path, "## Alpha\n\n- [ ] first\n")
            transport.content = files.readText(VAULT, path)
            open(path)

            assertThat(writes.addTask(VAULT, path, "Alpha", "second")).isEqualTo(WriteResult.Pushed)

            assertThat(files.readText(VAULT, path)).contains("- [ ] second")
            assertThat(updated()).isEmpty()
        }

    @Test
    fun `writing to a note that had already changed does not mark the change as seen`() =
        runTest {
            val path = "Tasks/Work.md"
            arrive(path, "## Alpha\n\n- [ ] first\n")
            open(path)
            arrive(path, "## Alpha\n\n- [ ] first\n- [ ] from the desk\n")
            transport.content = files.readText(VAULT, path)

            assertThat(writes.addTask(VAULT, path, "Alpha", "second")).isEqualTo(WriteResult.Pushed)

            assertThat(updated()).containsExactly(path)
        }

    private companion object {
        const val VAULT = 1L
        const val OTHER = 2L
    }
}
