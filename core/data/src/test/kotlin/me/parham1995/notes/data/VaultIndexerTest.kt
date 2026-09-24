package me.parham1995.notes.data

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The indexer, end to end over real files and a real database.
 *
 * Everything that broke on a device tonight lived in this path and nothing
 * here was covered: the transaction API, the title derivation, the search
 * index's separate copy of the title. Fixtures are synthetic -- the repository
 * is public and real vault paths would disclose the folder names.
 */
@RunWith(RobolectricTestRunner::class)
class VaultIndexerTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var indexer: VaultIndexer
    private lateinit var search: SearchIndex

    @Before
    fun setUp() {
        database = testDatabase()
        files = VaultFileStore(ApplicationProvider.getApplicationContext())
        search = SearchIndex(database)
        indexer =
            VaultIndexer(
                files = files,
                notes = database.noteDao(),
                links = database.linkDao(),
                headings = database.headingDao(),
                tasks = database.taskDao(),
                index = database.indexDao(),
                search = search,
                aliases = database.aliasDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun write(
        path: String,
        text: String,
    ): PathAndSha {
        files.write(1L, path, text.toByteArray())
        return PathAndSha(path, "sha-" + path.hashCode())
    }

    /** Rows in the search index, which no query of Room's can see. */
    private suspend fun ftsRows(): Long =
        database.useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM note_fts") { statement ->
                statement.step()
                statement.getLong(0)
            }
        }

    @Test
    fun `a note is titled by its file name, not its first heading`() =
        runTest {
            val entry = write("Alpha/Some File Name.md", "# A Completely Different Heading\n\nBody.")

            indexer.indexAll(1L, listOf(entry))

            val note = database.noteDao().byPath(1L, "Alpha/Some File Name.md")
            assertThat(note).isNotNull()
            assertThat(note!!.title).isEqualTo("Some File Name")
        }

    @Test
    fun `the search index gets the file name too`() =
        runTest {
            // It keeps its own copy, weighted ten times the body, so a stale
            // one there is worse than a stale one on screen.
            indexer.indexAll(1L, listOf(write("Alpha/Findable Note.md", "# Heading\n\nsome body text")))

            val hits = search.search(1L, "Findable")

            assertThat(hits).isNotEmpty()
            assertThat(hits.first().title).isEqualTo("Findable Note")
        }

    @Test
    fun `headings are captured with a scroll target`() =
        runTest {
            val entry = write("Note.md", "# One\n\ntext\n\n## Two\n\nmore\n\n### Three\n\nend")

            indexer.indexAll(1L, listOf(entry))

            val id = database.noteDao().idOf(1L, "Note.md")!!
            val headings = database.headingDao().byNote(id)
            assertThat(headings.map { it.text }).containsExactly("One", "Two", "Three").inOrder()
            assertThat(headings.map { it.level }).containsExactly(1, 2, 3).inOrder()
            // The block index is what an outline tap scrolls to.
            assertThat(headings.map { it.blockIndex }).isInOrder()
        }

    @Test
    fun `links resolve in a second pass, so forward references are not broken`() =
        runTest {
            // Alpha links to Beta before Beta has been indexed. Resolving
            // during the first pass would record this as broken.
            val alpha = write("Alpha.md", "See [[Beta]].")
            val beta = write("Beta.md", "The target.")

            indexer.indexAll(1L, listOf(alpha, beta))

            val betaId = database.noteDao().idOf(1L, "Beta.md")!!
            val backlinks = database.linkDao().backlinks(betaId)
            assertThat(backlinks).hasSize(1)
            assertThat(backlinks.first().title).isEqualTo("Alpha")
            assertThat(backlinks.first().context).contains("See")
        }

    @Test
    fun `a link with no target stays unresolved rather than vanishing`() =
        runTest {
            indexer.indexAll(1L, listOf(write("Alpha.md", "See [[Nothing Here]].")))

            val unresolved = database.linkDao().unresolved(1L)
            assertThat(unresolved.map { it.rawTarget }).contains("Nothing Here")
        }

    @Test
    fun `a folder note is recognised`() =
        runTest {
            indexer.indexAll(
                1L,
                listOf(
                    write("Beta/Beta.md", "The folder's landing page."),
                    write("Beta/Other.md", "Not a folder note."),
                ),
            )

            assertThat(database.noteDao().byPath(1L, "Beta/Beta.md")!!.isFolderNote).isTrue()
            assertThat(database.noteDao().byPath(1L, "Beta/Other.md")!!.isFolderNote).isFalse()
        }

    @Test
    fun `right-to-left content is flagged`() =
        runTest {
            indexer.indexAll(
                1L,
                listOf(
                    write("Fa.md", "این یک یادداشت فارسی است"),
                    write("En.md", "This one is not."),
                ),
            )

            assertThat(database.noteDao().byPath(1L, "Fa.md")!!.isRtl).isTrue()
            assertThat(database.noteDao().byPath(1L, "En.md")!!.isRtl).isFalse()
        }

    @Test
    fun `reindexing replaces rather than duplicates`() =
        runTest {
            val entry = write("Note.md", "# First\n\n[[Somewhere]]")
            indexer.indexAll(1L, listOf(entry))
            indexer.indexAll(1L, listOf(entry))

            val id = database.noteDao().idOf(1L, "Note.md")!!
            assertThat(database.headingDao().byNote(id)).hasSize(1)
            assertThat(database.linkDao().unresolved(1L).count { it.srcId == id }).isEqualTo(1)
        }

    @Test
    fun `a note that changed keeps nothing of what it used to say`() =
        runTest {
            // The incremental path a sync takes for every modified file, and
            // the one that was quietly broken: `@Upsert` answers -1 when it
            // resolves to an update, so a note that already existed had its
            // headings, links and tasks written against noteId -1. It updated
            // perfectly and kept the index it was first built with, and only a
            // full reindex -- which runs on an indexer version bump, so most
            // releases -- put it right.
            indexer.indexAll(1L, listOf(write("Note.md", "# Before\n\n- [ ] old task\n\n[[Alpha]]")))
            val id = database.noteDao().idOf(1L, "Note.md")!!

            indexer.indexChanged(
                vaultId = 1L,
                changed = listOf(write("Note.md", "# After\n\n- [ ] new task\n\n[[Beta]]")),
                removed = emptyList(),
            )

            assertThat(database.taskDao().byNote(id).map { it.text }).containsExactly("new task")
            assertThat(database.headingDao().byNote(id).map { it.text }).containsExactly("After")
            assertThat(
                database
                    .linkDao()
                    .unresolved(1L)
                    .filter { it.srcId == id }
                    .map { it.rawTarget },
            ).containsExactly("Beta")
        }

    @Test
    fun `a removed note leaves nothing behind`() =
        runTest {
            val alpha = write("Alpha.md", "[[Beta]]")
            indexer.indexAll(1L, listOf(alpha, write("Beta.md", "target")))
            val alphaId = database.noteDao().idOf(1L, "Alpha.md")!!

            indexer.indexChanged(1L, changed = emptyList(), removed = listOf("Alpha.md"))

            assertThat(database.noteDao().byPath(1L, "Alpha.md")).isNull()
            assertThat(database.headingDao().byNote(alphaId)).isEmpty()
            assertThat(search.search(1L, "Alpha").map { it.path }).doesNotContain("Alpha.md")
        }

    @Test
    fun `a full reindex leaves one search row per note`() =
        runTest {
            // It cleared the notes first and then asked the search index to
            // drop this vault's notes -- by which point there were none, so
            // every full reindex added a whole second copy of the vault.
            val entries = listOf(write("Alpha.md", "one"), write("Beta.md", "two"))
            indexer.indexAll(1L, entries)
            indexer.indexAll(1L, entries)

            assertThat(ftsRows()).isEqualTo(2L)
            assertThat(search.search(1L, "Alpha")).hasSize(1)
        }

    @Test
    fun `a full reindex drops what is no longer on disk, and only that`() =
        runTest {
            indexer.indexAll(
                1L,
                listOf(write("Keep.md", "- [ ] stays"), write("Gone.md", "- [ ] goes\n\n# Heading")),
            )
            val gone = database.noteDao().idOf(1L, "Gone.md")!!

            indexer.indexAll(1L, listOf(write("Keep.md", "- [ ] stays")))

            assertThat(database.noteDao().byPath(1L, "Gone.md")).isNull()
            assertThat(database.taskDao().byNote(gone)).isEmpty()
            assertThat(database.headingDao().byNote(gone)).isEmpty()
            assertThat(ftsRows()).isEqualTo(1L)
        }

    @Test
    fun `reindexing keeps what the reader did with a note`() =
        runTest {
            // The entity the indexer builds knows nothing about the reader, and
            // upserting it as it stood reset Recents and every scroll position
            // on each change -- and a full reindex renumbered every note too.
            indexer.indexAll(1L, listOf(write("Note.md", "before")))
            val id = database.noteDao().idOf(1L, "Note.md")!!
            database.noteDao().markOpened(id, 1234L)
            database.noteDao().rememberScroll(id, 7)

            indexer.indexChanged(1L, changed = listOf(write("Note.md", "after")), removed = emptyList())

            database.noteDao().byId(id)!!.let {
                assertThat(it.openedAt).isEqualTo(1234L)
                assertThat(it.scrollIndex).isEqualTo(7)
            }

            indexer.indexAll(1L, listOf(write("Note.md", "after again")))

            val note = database.noteDao().byPath(1L, "Note.md")!!
            assertThat(note.id).isEqualTo(id)
            assertThat(note.openedAt).isEqualTo(1234L)
            assertThat(note.scrollIndex).isEqualTo(7)
        }

    @Test
    fun `a note that moves keeps its backlinks`() =
        runTest {
            // The link from A still names B, but it pointed at the id B had
            // before the move, and resolving only revisits links with no
            // target -- so it stayed attached to a note that no longer existed.
            indexer.indexAll(1L, listOf(write("A.md", "see [[B]]"), write("X/B.md", "the target")))

            indexer.indexChanged(1L, changed = listOf(write("Y/B.md", "the target")), removed = listOf("X/B.md"))

            val moved = database.noteDao().idOf(1L, "Y/B.md")!!
            assertThat(database.linkDao().backlinks(moved).map { it.title }).containsExactly("A")
        }

    @Test
    fun `resolving leaves a link that is still broken alone`() =
        runTest {
            // Every pass used to write every unresolved link back as null --
            // the whole vault's broken links rewritten after each ticked task.
            indexer.indexAll(1L, listOf(write("A.md", "[[Nowhere]] and [[Nothing]] and [[B]]"), write("B.md", "b")))
            val before = writerChanges()

            indexer.indexChanged(1L, changed = emptyList(), removed = emptyList())

            assertThat(writerChanges() - before).isEqualTo(0L)
            assertThat(database.linkDao().unresolved(1L).map { it.rawTarget }).containsExactly("Nowhere", "Nothing")
        }

    /** Rows written through the writer connection so far. */
    private suspend fun writerChanges(): Long =
        database.useWriterConnection { connection ->
            connection.usePrepared("SELECT total_changes()") { statement ->
                statement.step()
                statement.getLong(0)
            }
        }

    @Test
    fun `a batch of notes is searchable, once each, after reindexing`() =
        runTest {
            // Written as one transaction per batch now rather than a commit per
            // note, and every note in it still has to come out the other side.
            val entries = (1..250).map { write("Batch/Note $it.md", "shared word plus unique$it") }
            indexer.indexAll(1L, entries)
            indexer.indexChanged(1L, changed = entries.take(3), removed = emptyList())

            assertThat(ftsRows()).isEqualTo(250L)
            assertThat(search.search(1L, "unique137").map { it.title }).containsExactly("Note 137")
        }
}
