package me.parham1995.notes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pins found again as notes, the way the home screen widget asks for them. */
@RunWith(RobolectricTestRunner::class)
class PinnedNotesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var indexer: VaultIndexer
    private lateinit var store: PinStore
    private lateinit var pinned: PinnedNotes

    @Before
    fun setUp() =
        runTest {
            context.settingsDataStore.edit { it.clear() }
            database = testDatabase()
            files = VaultFileStore(context)
            files.clear()
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
            store = PinStore(context)
            pinned = PinnedNotes(store, database.noteDao(), files)
        }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun index(
        vaultId: Long,
        vararg notes: Pair<String, String>,
    ) {
        val dao = database.vaultDao()
        if (dao.byId(vaultId) == null) {
            dao.insert(VaultEntity(id = vaultId, owner = "someone", repo = "repo-$vaultId", name = "v$vaultId"))
        }
        val entries =
            notes.map { (path, text) ->
                files.write(vaultId, path, text.toByteArray())
                PathAndSha(path, "sha-" + path.hashCode())
            }
        indexer.indexAll(vaultId, entries)
    }

    @Test
    fun `pins come back as notes, newest first, with their opening lines`() =
        runTest {
            index(1, "Shopping.md" to "# Shopping\n\n- [ ] bread\n- [x] milk", "Ideas.md" to "one\n\ntwo")
            store.pin(1, "Shopping.md")
            store.pin(1, "Ideas.md")

            val notes = pinned.resolve(1, limit = 10)

            assertThat(notes.map { it.title }).containsExactly("Ideas", "Shopping").inOrder()
            assertThat(notes.last().excerpt).isEqualTo("☐ bread\n☑ milk")
            assertThat(notes.first().noteId).isEqualTo(database.noteDao().idOf(1, "Ideas.md"))
        }

    @Test
    fun `only the vault asked about`() =
        runTest {
            index(1, "README.md" to "first vault")
            index(2, "README.md" to "second vault")
            store.pin(1, "README.md")
            store.pin(2, "README.md")

            val first = pinned.resolve(1, limit = 10).single()
            assertThat(first.vaultId).isEqualTo(1L)
            assertThat(first.excerpt).isEqualTo("first vault")
            assertThat(first.noteId).isEqualTo(database.noteDao().idOf(1, "README.md"))
        }

    @Test
    fun `a pin whose note is gone is not drawn, and is forgotten`() =
        runTest {
            index(1, "Kept.md" to "here")
            store.pin(1, "Deleted.md")
            store.pin(1, "Kept.md")

            assertThat(pinned.resolve(1, limit = 10).map { it.path }).containsExactly("Kept.md")
            assertThat(store.pins.first()).containsExactly(Pin(1, "Kept.md"))
        }

    @Test
    fun `a vault with nothing indexed yet keeps its pins`() =
        runTest {
            // Before the first sync, or after the database was cleared: the
            // notes are coming back, and the pins have to be there for them.
            store.pin(5, "Later.md")

            assertThat(pinned.resolve(5, limit = 10)).isEmpty()
            assertThat(store.pins.first()).containsExactly(Pin(5, "Later.md"))
        }

    @Test
    fun `the limit is respected without forgetting the pins beyond it`() =
        runTest {
            index(1, "A.md" to "a", "B.md" to "b", "C.md" to "c")
            store.pin(1, "A.md")
            store.pin(1, "B.md")
            store.pin(1, "C.md")

            assertThat(pinned.resolve(1, limit = 2).map { it.path }).containsExactly("C.md", "B.md").inOrder()
            assertThat(store.pins.first()).hasSize(3)
        }

    @Test
    fun `a long note is read only as far as its excerpt needs`() =
        runTest {
            val long = (1..20_000).joinToString("\n\n") { "paragraph $it" }
            index(1, "Long.md" to long)
            store.pin(1, "Long.md")

            val note = pinned.resolve(1, limit = 1, lines = 3).single()
            assertThat(note.excerpt).isEqualTo("paragraph 1\nparagraph 2\nparagraph 3")
        }

    @Test
    fun `a cut through the middle of a line drops that line`() {
        val bytes = "first\nسلام".toByteArray()
        // Cut inside the second line, part way through a Persian letter.
        val text = PinnedNotes.headText(bytes, bytes.size - 1, truncated = true)
        assertThat(text).isEqualTo("first\n")
        assertThat(PinnedNotes.headText(bytes, bytes.size, truncated = false)).isEqualTo("first\nسلام")
    }

    @Test
    fun `one line longer than the budget is kept, less the broken character`() {
        val bytes = "سلام".toByteArray()
        val text = PinnedNotes.headText(bytes, bytes.size - 1, truncated = true)
        assertThat(text).isEqualTo("سلا")
    }
}
