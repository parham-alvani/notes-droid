package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tree the browser draws, and the note it opens.
 *
 * The browser shipped empty because this was queried once at startup and never
 * again, which no test would have caught while they all called it directly.
 * The reactive path is therefore tested as a flow, not as a function.
 */
@RunWith(RobolectricTestRunner::class)
class VaultRepositoryTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var indexer: VaultIndexer
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        database = testDatabase()
        files = VaultFileStore(ApplicationProvider.getApplicationContext())
        val search = SearchIndex(database)
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
        repository =
            VaultRepository(
                notes = database.noteDao(),
                links = database.linkDao(),
                headings = database.headingDao(),
                files = files,
                search = search,
                tasks = database.taskDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun index(vararg notes: Pair<String, String>) {
        val entries =
            notes.map { (path, text) ->
                files.write(path, text.toByteArray())
                PathAndSha(path, "sha-" + path.hashCode())
            }
        indexer.indexAll(entries)
    }

    @Test
    fun `the root lists top level folders and loose notes`() =
        runTest {
            index(
                "Alpha/One.md" to "a",
                "Alpha/Deep/Two.md" to "b",
                "Beta/Three.md" to "c",
                "Loose.md" to "d",
            )

            val items = repository.children("")

            assertThat(items.filter { it.isFolder }.map { it.name }).containsExactly("Alpha", "Beta")
            assertThat(items.filterNot { it.isFolder }.map { it.name }).containsExactly("Loose")
            // Only one level: Deep belongs to Alpha, not to the root.
            assertThat(items.map { it.name }).doesNotContain("Deep")
        }

    @Test
    fun `descending shows that folder's own children`() =
        runTest {
            index("Alpha/One.md" to "a", "Alpha/Deep/Two.md" to "b")

            val items = repository.children("Alpha")

            assertThat(items.filter { it.isFolder }.map { it.name }).containsExactly("Deep")
            assertThat(items.filterNot { it.isFolder }.map { it.name }).containsExactly("One")
        }

    @Test
    fun `a folder note is reachable from the folder and hidden inside it`() =
        runTest {
            index("Alpha/Alpha.md" to "the landing page", "Alpha/One.md" to "a")

            val root = repository.children("")
            val alpha = root.single { it.name == "Alpha" }
            assertThat(alpha.isFolder).isTrue()
            // Tapping the folder opens its own note.
            assertThat(alpha.noteId).isNotNull()

            // And it is not listed again inside itself.
            assertThat(repository.children("Alpha").map { it.name }).containsExactly("One")
        }

    @Test
    fun `a folder note says so, and its folder's contents are one call away`() =
        runTest {
            // What the reader needs to offer both halves of a folder: the page
            // someone wrote, and the things actually in it.
            index(
                "Alpha/Alpha.md" to "the landing page",
                "Alpha/One.md" to "a",
                "Alpha/Deep/Deep.md" to "a nested landing page",
                "Beta/Ordinary.md" to "b",
            )

            val landing = repository.note(database.noteDao().idOf("Alpha/Alpha.md")!!)!!
            assertThat(landing.isFolderNote).isTrue()

            // `A/B/B.md` is the landing page for `A/B`, so that is the folder
            // whose contents belong beside it -- and it does not list itself.
            val contents = repository.children(landing.path.substringBeforeLast('/', ""))
            assertThat(contents.map { it.name }).containsExactly("Deep", "One")
            assertThat(contents.single { it.name == "Deep" }.noteId).isNotNull()

            val ordinary = repository.note(database.noteDao().idOf("Beta/Ordinary.md")!!)!!
            assertThat(ordinary.isFolderNote).isFalse()
        }

    @Test
    fun `the tree fills in as notes arrive`() =
        runTest {
            // This is the bug the browser shipped with: queried once, before
            // the first sync, and never again.
            assertThat(repository.childrenFlow("").first()).isEmpty()

            index("Alpha/One.md" to "a")

            assertThat(repository.childrenFlow("").first().map { it.name }).containsExactly("Alpha")
        }

    @Test
    fun `opening a note renders it and resolves its links`() =
        runTest {
            index(
                "Alpha/Source.md" to "# Heading\n\nSee [[Target]] and [[Nowhere]].",
                "Beta/Target.md" to "the target",
            )
            val id = database.noteDao().idOf("Alpha/Source.md")!!

            val note = repository.note(id)!!

            assertThat(note.title).isEqualTo("Source")
            assertThat(note.blocks).isNotEmpty()
            assertThat(note.linkTargets).containsKey("Target")
            // A link with no destination is kept and marked, not dropped.
            assertThat(note.brokenTargets).contains("Nowhere")
        }

    @Test
    fun `backlinks name the source and quote the line`() =
        runTest {
            index(
                "Alpha.md" to "a mention of [[Target]] in passing",
                "Target.md" to "the target",
            )
            val targetId = database.noteDao().idOf("Target.md")!!

            val backlinks = repository.backlinks(targetId)

            assertThat(backlinks).hasSize(1)
            assertThat(backlinks.first().title).isEqualTo("Alpha")
            assertThat(backlinks.first().context).contains("in passing")
        }

    @Test
    fun `the quick switcher matches on name`() =
        runTest {
            index("Alpha/Kubernetes Networking.md" to "x", "Beta/Other.md" to "y")

            val hits = repository.quickSwitch("kubernetes")

            assertThat(hits.map { it.name }).containsExactly("Kubernetes Networking")
        }

    @Test
    fun `indexing lifts tasks out of the notes and orders them by when they are due`() =
        runTest {
            index(
                "Work/Apollo.md" to
                    """
                    ## Launch

                    - [ ] late thing ⏳ 2026-01-01
                    - [ ] next week ⏳ 2026-09-25
                    - [x] finished ✅ 2026-09-01
                    - [ ] someday
                    """.trimIndent(),
            )

            val open = repository.openTasks().first()

            // Closed tasks are not part of a list of what to do.
            assertThat(open.map { it.text })
                .containsExactly("late thing", "next week", "someday")
                .inOrder()
            // The heading above a task is how this vault names the project.
            assertThat(open.first().section).isEqualTo("Launch")
            assertThat(open.first().noteTitle).isEqualTo("Apollo")
            // Undated last, rather than first as SQLite would sort NULL.
            assertThat(open.last().actionableOn).isNull()
        }

    @Test
    fun `reindexing a note replaces its tasks instead of doubling them`() =
        runTest {
            index("Work/Apollo.md" to "- [ ] one ⏳ 2026-09-18")
            index("Work/Apollo.md" to "- [ ] one ⏳ 2026-09-18\n- [ ] two")

            assertThat(repository.openTasks().first()).hasSize(2)
        }

    @Test
    fun `a note that goes away takes its tasks with it`() =
        runTest {
            index("Work/Apollo.md" to "- [ ] one ⏳ 2026-09-18")
            indexer.indexChanged(changed = emptyList(), removed = listOf("Work/Apollo.md"))

            assertThat(repository.openTasks().first()).isEmpty()
        }
}
