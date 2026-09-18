package me.parham1995.notes.data

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
}
