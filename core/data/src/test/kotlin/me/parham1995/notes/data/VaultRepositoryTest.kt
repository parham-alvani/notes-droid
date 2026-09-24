package me.parham1995.notes.data

import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
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

    /** Vault ids the tests index into. The first is the active one. */
    private val first = 1L
    private val second = 2L

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
                aliases = database.aliasDao(),
            )
        repository =
            VaultRepository(
                notes = database.noteDao(),
                links = database.linkDao(),
                headings = database.headingDao(),
                files = files,
                search = search,
                tasks = database.taskDao(),
                blobs = database.blobDao(),
                vaults = database.vaultDao(),
                settings = SettingsStore(ApplicationProvider.getApplicationContext()),
                tags = database.tagDao(),
                aliases = database.aliasDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun index(vararg notes: Pair<String, String>) = indexInto(first, *notes)

    private suspend fun ensureVault(vaultId: Long) {
        val dao = database.vaultDao()
        if (dao.byId(vaultId) == null) {
            dao.insert(VaultEntity(id = vaultId, owner = "someone", repo = "repo-$vaultId", name = "v$vaultId"))
        }
    }

    private suspend fun indexInto(
        vaultId: Long,
        vararg notes: Pair<String, String>,
    ) {
        ensureVault(vaultId)
        val entries =
            notes.map { (path, text) ->
                files.write(vaultId, path, text.toByteArray())
                PathAndSha(path, "sha-" + path.hashCode())
            }
        indexer.indexAll(vaultId, entries)
    }

    /** Records the one config file the vault syncs, as a sync would. */
    private suspend fun config(path: String) {
        ensureVault(first)
        database.blobDao().upsert(
            me.parham1995.notes.data.database.BlobEntity(
                path = path,
                vaultId = first,
                sha = "sha-" + path.hashCode(),
                size = 1,
                kind = me.parham1995.notes.sync.BlobKind.CONFIG,
                localState = me.parham1995.notes.sync.LocalState.DOWNLOADED,
            ),
        )
    }

    /** Records a file the reader does not parse, as a sync would. */
    private suspend fun attachment(path: String) {
        // The vault has to exist for anything to be active, and a repository of
        // nothing but attachments never indexes a note to create one.
        ensureVault(first)
        database.blobDao().upsert(
            me.parham1995.notes.data.database.BlobEntity(
                path = path,
                vaultId = first,
                sha = "sha-" + path.hashCode(),
                size = 1,
                kind = me.parham1995.notes.sync.BlobKind.OTHER,
                localState = me.parham1995.notes.sync.LocalState.ABSENT,
            ),
        )
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

            val landing = repository.note(database.noteDao().idOf(1L, "Alpha/Alpha.md")!!)!!
            assertThat(landing.isFolderNote).isTrue()

            // `A/B/B.md` is the landing page for `A/B`, so that is the folder
            // whose contents belong beside it -- and it does not list itself.
            val contents = repository.children(landing.path.substringBeforeLast('/', ""))
            assertThat(contents.map { it.name }).containsExactly("Deep", "One")
            assertThat(contents.single { it.name == "Deep" }.noteId).isNotNull()

            val ordinary = repository.note(database.noteDao().idOf(1L, "Beta/Ordinary.md")!!)!!
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
    fun `opening a note elsewhere does not redraw this folder`() =
        runTest {
            // Room re-runs a query on any write to its table, and opening or
            // scrolling a note writes to `notes`. A folder that did not change
            // should not be rebuilt -- and used to be, with a lookup per
            // subfolder each time.
            index("Alpha/One.md" to "a", "Alpha/Sub/Sub.md" to "landing", "Beta/Two.md" to "b")
            val elsewhere = database.noteDao().idOf(first, "Beta/Two.md")!!
            val emissions = java.util.concurrent.CopyOnWriteArrayList<List<VaultItem>>()
            backgroundScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                repository.childrenFlow("Alpha").collect { emissions += it }
            }
            awaitUntil { emissions.size == 1 }

            repository.markOpened(elsewhere)
            repository.rememberScroll(elsewhere, 4)
            // Something that does change this folder, so there is a point at
            // which every earlier write has certainly been seen.
            index("Alpha/One.md" to "a", "Alpha/Sub/Sub.md" to "landing", "Beta/Two.md" to "b", "Alpha/New.md" to "c")
            awaitUntil { emissions.last().any { it.name == "New" } }

            assertThat(emissions).hasSize(2)
            assertThat(emissions.last().single { it.name == "Sub" }.noteId).isNotNull()
        }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(POLL_MILLIS)
        }
        // Long enough for a stray emission behind this one to arrive.
        Thread.sleep(SETTLE_MILLIS)
    }

    @Test
    fun `opening a note renders it and resolves its links`() =
        runTest {
            index(
                "Alpha/Source.md" to "# Heading\n\nSee [[Target]] and [[Nowhere]].",
                "Beta/Target.md" to "the target",
            )
            val id = database.noteDao().idOf(1L, "Alpha/Source.md")!!

            val note = repository.note(id)!!

            assertThat(note.title).isEqualTo("Source")
            assertThat(note.blocks).isNotEmpty()
            assertThat(note.linkTargets).containsKey("Target")
            // A link with no destination is kept and marked, not dropped.
            assertThat(note.brokenTargets).contains("Nowhere")
        }

    @Test
    fun `an image embedded by its bare name is found in its own vault`() =
        runTest {
            // `![[photo.png]]` is what Obsidian writes for a file anywhere in
            // the vault. It used to be looked up at the root, and the other
            // vault holding a file of the same name must not answer for it.
            index("Journal/Day.md" to "![[photo.png]]\n\n![scan](Scans/first%20page.png)")
            attachment("Assets/2024/photo.png")
            attachment("Scans/first page.png")
            ensureVault(second)
            database.blobDao().upsert(
                me.parham1995.notes.data.database.BlobEntity(
                    path = "photo.png",
                    vaultId = second,
                    sha = "other",
                    size = 1,
                    kind = me.parham1995.notes.sync.BlobKind.IMAGE,
                    localState = me.parham1995.notes.sync.LocalState.DOWNLOADED,
                ),
            )

            val note = repository.note(database.noteDao().idOf(first, "Journal/Day.md")!!)!!

            val images = note.blocks.filterIsInstance<me.parham1995.notes.markdown.MdBlock.Image>()
            assertThat(images.map { it.path }).containsExactly("Assets/2024/photo.png", "Scans/first page.png")
        }

    @Test
    fun `backlinks name the source and quote the line`() =
        runTest {
            index(
                "Alpha.md" to "a mention of [[Target]] in passing",
                "Target.md" to "the target",
            )
            val targetId = database.noteDao().idOf(1L, "Target.md")!!

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
    fun `the quick switcher reads a wildcard as the character it is`() =
        runTest {
            // `%` and `_` mean "anything" to LIKE and are ordinary in a name.
            index(
                "100% Done.md" to "a",
                "1000 Things.md" to "b",
                "snake_case.md" to "c",
                "snakescase.md" to "d",
                "back\\slash.md" to "e",
            )

            assertThat(repository.quickSwitch("100%").map { it.name }).containsExactly("100% Done")
            assertThat(repository.quickSwitch("snake_").map { it.name }).containsExactly("snake_case")
            assertThat(repository.quickSwitch("back\\").map { it.name }).containsExactly("back\\slash")
        }

    @Test
    fun `the quick switcher only offers notes from the vault being read`() =
        runTest {
            // The one query in the app that was never scoped, and the one that
            // answers on every keystroke -- so typing a name reached into a
            // repository the reader is not even looking at.
            indexInto(first, "Alpha/Kubernetes Networking.md" to "x")
            indexInto(second, "Archive/Kubernetes Invoice.md" to "y")

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
    fun `the digest counts only tasks that belong to a note`() =
        runTest {
            index(
                "Work/Apollo.md" to "- [ ] late ⏳ 2026-01-01\n- [ ] today ⏳ 2026-09-23\n- [ ] later ⏳ 2026-12-01",
            )
            // What an older reindex left behind: rows for a note id that no
            // longer exists, which nothing cascades away.
            database.indexDao().insertTasks(
                listOf("2026-01-01", "2026-09-23").mapIndexed { ordinal, date ->
                    me.parham1995.notes.data.database.TaskEntity(
                        noteId = 999_999,
                        text = "orphan",
                        state = "OPEN",
                        section = "",
                        blockIndex = 0,
                        ordinal = ordinal,
                        open = true,
                        actionableOn = date,
                        scheduled = null,
                        due = null,
                        done = null,
                        recurring = null,
                    )
                },
            )

            assertThat(repository.overdueCount("2026-09-23")).isEqualTo(1)
            assertThat(repository.dueTodayCount("2026-09-23")).isEqualTo(1)
        }

    @Test
    fun `a note that goes away takes its tasks with it`() =
        runTest {
            index("Work/Apollo.md" to "- [ ] one ⏳ 2026-09-18")
            indexer.indexChanged(1L, changed = emptyList(), removed = listOf("Work/Apollo.md"))

            assertThat(repository.openTasks().first()).isEmpty()
        }

    @Test
    fun `a repository of documents is not an empty tree`() =
        runTest {
            // The case this was missing entirely: 63 PDFs, 82 images and one
            // markdown file. Building the browser from notes alone showed a
            // folder with one note in it and nothing else.
            attachment("Papers/2024/lease.pdf")
            attachment("Papers/passport.jpg")
            attachment("loose.pdf")

            val root = repository.children("")
            assertThat(root.map { it.name }).containsExactly("Papers", "loose.pdf")
            assertThat(root.single { it.name == "Papers" }.isFolder).isTrue()
            assertThat(root.single { it.name == "loose.pdf" }.isAttachment).isTrue()

            val papers = repository.children("Papers")
            // A folder implied only by the attachments below it still appears.
            assertThat(papers.map { it.name }).containsExactly("2024", "passport.jpg")
            assertThat(papers.single { it.name == "2024" }.isFolder).isTrue()
        }

    @Test
    fun `the icon assignments are not a folder in the tree`() =
        runTest {
            // `.obsidian` is not vault content, but the one file under it that
            // syncs was being listed as an attachment -- so the browser
            // derived a folder from its path and put it at the top of the
            // tree, offering to open the plugin's config.
            config(me.parham1995.notes.sync.VaultFilter.ICONIC_CONFIG)
            attachment("uploads/logo.png")

            val root = repository.children("")

            assertThat(root.map { it.name }).containsExactly("uploads")
        }

    @Test
    fun `a folder guide is kept on the device but is not a note`() =
        runTest {
            // Synced like anything else -- this is only about what is shown.
            // There is a CLAUDE.md in nearly every folder worth browsing, so
            // left as notes they sit at every level of the tree and answer to
            // any search for a word about conventions.
            index(
                "Learning/CLAUDE.md" to "# How to write notes here",
                "Learning/Kafka.md" to "a real note",
            )

            assertThat(repository.children("Learning").map { it.name }).containsExactly("Kafka")
            assertThat(repository.quickSwitch("claude")).isEmpty()
            assertThat(repository.search("conventions")).isEmpty()
        }

    @Test
    fun `a guide that was indexed before stops being a note`() =
        runTest {
            // The transition an existing install makes. Bumping the indexer's
            // version rebuilds from scratch, but a guide arriving in an
            // ordinary sync has to drop the row it already had -- so the row
            // is put there the way the old indexer would have left it.
            ensureVault(first)
            files.write(first, "Learning/CLAUDE.md", "# How to write notes here".toByteArray())
            database.noteDao().upsert(
                me.parham1995.notes.data.database.NoteEntity(
                    vaultId = first,
                    path = "Learning/CLAUDE.md",
                    parent = "Learning",
                    name = "CLAUDE",
                    slug = "claude",
                    title = "CLAUDE",
                    blobSha = "sha-old",
                    size = 1,
                    isFolderNote = false,
                    isRtl = false,
                    hasMermaid = false,
                    hasMath = false,
                    indexedAt = 0,
                ),
            )
            assertThat(repository.children("Learning").map { it.name }).containsExactly("CLAUDE")

            indexer.indexChanged(
                vaultId = first,
                changed = listOf(PathAndSha("Learning/CLAUDE.md", "sha-changed")),
                removed = emptyList(),
            )

            assertThat(repository.children("Learning")).isEmpty()
        }

    @Test
    fun `notes and files share a folder without hiding each other`() =
        runTest {
            index("Papers/Notes.md" to "a note")
            attachment("Papers/lease.pdf")

            assertThat(repository.children("Papers").map { it.name })
                .containsExactly("Notes", "lease.pdf")
        }

    @Test
    fun `a note that names another without linking is an unlinked mention`() =
        runTest {
            index(
                "Infra/Rate Limiting.md" to "how the limiter works",
                "Infra/Gateway.md" to "the gateway does Rate Limiting at the edge",
                "Infra/Linked.md" to "see [[Rate Limiting]] for the details",
                "Infra/Unrelated.md" to "nothing to do with it",
            )
            val id = database.noteDao().idOf(1L, "Infra/Rate Limiting.md")!!

            val mentions = repository.unlinkedMentions(id)

            // Gateway says the name and never links it. Linked already does, so
            // it belongs under backlinks instead of being reported twice.
            assertThat(mentions.map { it.title }).containsExactly("Gateway")
        }

    @Test
    fun `a note is never an unlinked mention of itself`() =
        runTest {
            index("Infra/Rate Limiting.md" to "Rate Limiting is what this note is about")
            val id = database.noteDao().idOf(1L, "Infra/Rate Limiting.md")!!

            assertThat(repository.unlinkedMentions(id)).isEmpty()
        }

    @Test
    fun `a mention has to be the whole phrase, not one of its words`() =
        runTest {
            index(
                "Infra/Rate Limiting.md" to "the subject",
                "Infra/Money.md" to "the exchange rate moved",
            )
            val id = database.noteDao().idOf(1L, "Infra/Rate Limiting.md")!!

            // "rate" alone is not a mention of "Rate Limiting", and treating it
            // as one would bury the real mentions in a vault this size.
            assertThat(repository.unlinkedMentions(id)).isEmpty()
        }

    @Test
    fun `a note can be shared exactly as it is written`() =
        runTest {
            val text = "# Heading\n\nBody with [[a link]] and **emphasis**."
            index("Infra/Source.md" to text)
            val id = database.noteDao().idOf(1L, "Infra/Source.md")!!

            // Markdown, not the rendered text: what goes out is what the person
            // receiving it can do something with.
            assertThat(repository.markdown(id)).isEqualTo(text)
        }

    @Test
    fun `the graph knows which way each link points`() =
        runTest {
            index(
                "A.md" to "links to [[B]] and [[C]]",
                "B.md" to "links back to [[A]]",
                "C.md" to "says nothing",
                "D.md" to "links to [[A]]",
            )
            val a = database.noteDao().idOf(1L, "A.md")!!

            val graph = repository.neighbours(a)!!

            assertThat(graph.outgoing.map { it.title }).containsExactly("B", "C")
            assertThat(graph.incoming.map { it.title }).containsExactly("B", "D")
            // B is both, which in a hand-linked vault is the strongest signal
            // there is, and is drawn differently because of it.
            assertThat(graph.mutual).containsExactly(database.noteDao().idOf(1L, "B.md"))
        }

    @Test
    fun `a note referenced four times is one edge, not four`() =
        runTest {
            index(
                "A.md" to "see [[B]], and [[B]] again, and [[B]] once more",
                "B.md" to "the target",
            )
            val a = database.noteDao().idOf(1L, "A.md")!!

            assertThat(repository.neighbours(a)!!.outgoing).hasSize(1)
        }

    @Test
    fun `an unconnected note has an empty graph rather than no graph`() =
        runTest {
            index("Lonely.md" to "nothing here")
            val id = database.noteDao().idOf(1L, "Lonely.md")!!

            val graph = repository.neighbours(id)!!

            assertThat(graph.outgoing).isEmpty()
            assertThat(graph.incoming).isEmpty()
            assertThat(graph.centre.title).isEqualTo("Lonely")
        }

    @Test
    fun `the graph of a note that is not there is null`() =
        runTest {
            assertThat(repository.neighbours(9_999)).isNull()
        }

    @Test
    fun `two vaults holding the same path hold different notes`() =
        runTest {
            indexInto(first, "README.md" to "the first vault")
            indexInto(second, "README.md" to "the second vault")

            // The unique index is on (vault, path), not path: a README in each
            // is two notes, and treating them as one is how mounting went wrong.
            assertThat(database.noteDao().byPath(first, "README.md")!!.vaultId).isEqualTo(first)
            assertThat(database.noteDao().byPath(second, "README.md")!!.vaultId).isEqualTo(second)
            assertThat(database.noteDao().count(first).first()).isEqualTo(1)
            assertThat(database.noteDao().count(second).first()).isEqualTo(1)
        }

    @Test
    fun `a link cannot resolve into another vault`() =
        runTest {
            indexInto(second, "Target.md" to "the other vault's note")
            indexInto(first, "Source.md" to "see [[Target]]")

            val id = database.noteDao().idOf(first, "Source.md")!!
            val note = repository.note(id)!!

            // It is a broken link, not a link across vaults. Separate vaults
            // that quietly linked into each other would be mounting again.
            assertThat(note.linkTargets).isEmpty()
            assertThat(note.brokenTargets).contains("Target")
        }

    @Test
    fun `the browser only lists the active vault`() =
        runTest {
            indexInto(first, "Only/Mine.md" to "a")
            indexInto(second, "Theirs/Yours.md" to "b")

            assertThat(repository.children("").map { it.name }).containsExactly("Only")
        }

    @Test
    fun `indexing one vault leaves the other alone`() =
        runTest {
            indexInto(first, "A.md" to "a")
            indexInto(second, "B.md" to "b")

            // `indexAll` clears before it writes, and clearing everything would
            // silently empty whichever vault was not being reindexed.
            indexInto(first, "A.md" to "a changed")

            assertThat(database.noteDao().count(second).first()).isEqualTo(1)
        }

    // -- tags and aliases --------------------------------------------------

    @Test
    fun `tags are indexed, and a parent tag finds its children's notes`() =
        runTest {
            index(
                "A.md" to "---\ntags: [project/alpha]\n---\nbody",
                "B.md" to "about #project/beta and #Idea",
                "C.md" to "nothing tagged, #projects is another tag",
            )

            assertThat(repository.notesTagged(first, "project").map { it.name }).containsExactly("A", "B").inOrder()
            assertThat(repository.notesTagged(first, "project/beta").map { it.name }).containsExactly("B")
            // Case is not a different tag.
            assertThat(repository.notesTagged(first, "#idea").map { it.name }).containsExactly("B")

            val tree = repository.tagTree(first).first()
            assertThat(tree.map { it.path to it.count }).containsExactly("Idea" to 1, "project" to 2, "projects" to 1)
        }

    @Test
    fun `tags belong to their own vault`() =
        runTest {
            indexInto(first, "Mine.md" to "#shared")
            indexInto(second, "Theirs.md" to "#shared #only-there")

            assertThat(repository.notesTagged(first, "shared").map { it.name }).containsExactly("Mine")
            assertThat(repository.tagTree(first).first().map { it.path }).containsExactly("shared")
        }

    @Test
    fun `a tag an edited note no longer carries is gone, and so is a removed note's`() =
        runTest {
            index("A.md" to "#old", "B.md" to "#kept")
            index("A.md" to "#new", "B.md" to "#kept")
            assertThat(repository.notesTagged(first, "old")).isEmpty()
            assertThat(repository.notesTagged(first, "new").map { it.name }).containsExactly("A")

            indexer.indexChanged(first, changed = emptyList(), removed = listOf("A.md", "B.md"))
            assertThat(repository.tagTree(first).first()).isEmpty()
            // Counted raw: every query joins `notes`, so an orphaned row is
            // invisible to all of them and would sit there for good.
            assertThat(rows("tags")).isEqualTo(0)
        }

    @Test
    fun `a removed note takes its aliases with it`() =
        runTest {
            index("A.md" to "---\naliases: [Other]\n---\n")
            assertThat(rows("aliases")).isEqualTo(1)

            indexer.indexChanged(first, changed = emptyList(), removed = listOf("A.md"))
            assertThat(rows("aliases")).isEqualTo(0)
        }

    private suspend fun rows(table: String): Long =
        database.useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
                statement.step()
                statement.getLong(0)
            }
        }

    @Test
    fun `a link finds a note by its alias, in the index and on the page`() =
        runTest {
            index(
                "People/Jane Doe.md" to "---\naliases: [Jane, JD]\n---\nabout her",
                "Source.md" to "met [[Jane]] and [[jd]] and [[Nobody]]",
            )

            val jane = database.noteDao().idOf(first, "People/Jane Doe.md")!!
            assertThat(repository.backlinks(jane).map { it.title }).containsExactly("Source", "Source")

            val source = repository.note(database.noteDao().idOf(first, "Source.md")!!)!!
            assertThat(source.linkTargets).containsExactly("Jane", jane, "jd", jane)
            assertThat(source.brokenTargets).containsExactly("Nobody")
        }

    @Test
    fun `a real name beats an alias`() =
        runTest {
            index(
                "Jane.md" to "the real one",
                "Other.md" to "---\nalias: Jane\n---\n",
                "Source.md" to "[[Jane]]",
            )

            val real = database.noteDao().idOf(first, "Jane.md")!!
            val source = repository.note(database.noteDao().idOf(first, "Source.md")!!)!!
            assertThat(source.linkTargets["Jane"]).isEqualTo(real)
            assertThat(repository.backlinks(real)).hasSize(1)
        }

    @Test
    fun `an alias taken away lets go of the links that used it`() =
        runTest {
            index("Target.md" to "---\naliases: [Nick]\n---\n", "Source.md" to "[[Nick]]")
            val target = database.noteDao().idOf(first, "Target.md")!!
            assertThat(repository.backlinks(target)).hasSize(1)

            // Only the target changes; the link in Source is not reparsed.
            files.write(first, "Target.md", "no aliases now".toByteArray())
            indexer.indexChanged(first, changed = listOf(PathAndSha("Target.md", "sha-changed")), removed = emptyList())

            assertThat(repository.backlinks(target)).isEmpty()
            assertThat(database.linkDao().unresolved(first).map { it.rawTarget }).contains("Nick")
        }

    @Test
    fun `an alias cannot be reached from another vault`() =
        runTest {
            indexInto(second, "Target.md" to "---\naliases: [Nick]\n---\n")
            indexInto(first, "Source.md" to "[[Nick]]")

            val note = repository.note(database.noteDao().idOf(first, "Source.md")!!)!!
            assertThat(note.brokenTargets).containsExactly("Nick")
            assertThat(repository.quickSwitch("nick")).isEmpty()
        }

    @Test
    fun `the quick switcher offers a note by its alias`() =
        runTest {
            index(
                "People/Jane Doe.md" to "---\naliases: [Janie]\n---\n",
                "Janitor.md" to "x",
                "Unrelated.md" to "y",
            )

            // A name that starts with it first, then an alias that does.
            assertThat(repository.quickSwitch("jani").map { it.name }).containsExactly("Janitor", "Jane Doe").inOrder()
        }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
        const val SETTLE_MILLIS = 300L
    }
}
