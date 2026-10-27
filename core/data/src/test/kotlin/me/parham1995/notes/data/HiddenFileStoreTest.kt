package me.parham1995.notes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.BlobEntity
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.VaultFilter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hidden files are left out of every list and still work as targets.
 *
 * Each list is asked here, rather than trusting that one query with
 * `hidden = 0` means all of them have it: the mark is a column every list
 * has to remember, which is exactly the shape of the bugs this project has
 * had before. And every target is followed, because hiding a file by not
 * indexing it, the way the guides used to be, hid it from links too.
 *
 * Every fixture is made up. The repository is public; the vault is not.
 */
@RunWith(RobolectricTestRunner::class)
class HiddenFileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var settings: SettingsStore
    private lateinit var hidden: HiddenFileStore
    private lateinit var indexer: VaultIndexer
    private lateinit var repository: VaultRepository

    private val vault = 1L

    @Before
    fun setUp() =
        runTest {
            // One DataStore per file for the life of the JVM: whatever the last
            // test switched is still switched.
            context.settingsDataStore.edit { it.clear() }
            database = testDatabase()
            files = VaultFileStore(context)
            settings = SettingsStore(context)
            hidden = testHiddenFiles(database, files, settings)
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
                    hidden = hidden,
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
                    settings = settings,
                    tags = database.tagDao(),
                    aliases = database.aliasDao(),
                    hidden = hidden,
                )
            database.vaultDao().insert(VaultEntity(id = vault, owner = "someone", repo = "garden", name = "Garden"))
        }

    @After
    fun tearDown() =
        runTest {
            database.close()
            files.clear()
        }

    /** Writes [notes] to disk and indexes them all, as a reindex would. */
    private suspend fun index(vararg notes: Pair<String, String>) {
        val entries =
            notes.map { (path, text) ->
                files.write(vault, path, text.toByteArray())
                file(path, BlobKind.MARKDOWN)
                PathAndSha(path, "sha-" + path.hashCode())
            }
        indexer.indexAll(vault, entries)
    }

    /** Records a file in the manifest, the way a sync would. */
    private suspend fun file(
        path: String,
        kind: BlobKind,
    ) {
        database.blobDao().upsert(
            BlobEntity(
                path = path,
                vaultId = vault,
                sha = "sha-" + path.hashCode(),
                size = 1,
                kind = kind,
                localState = LocalState.DOWNLOADED,
            ),
        )
    }

    /** Obsidian's app settings with [filters] as its excluded files, synced. */
    private suspend fun excluded(vararg filters: String) {
        val list = filters.joinToString(", ") { "\"$it\"" }
        files.write(
            vault,
            VaultFilter.APP,
            """{"alwaysUpdateLinks": true, "userIgnoreFilters": [$list]}""".toByteArray(),
        )
        file(VaultFilter.APP, BlobKind.CONFIG)
    }

    private suspend fun idOf(path: String): Long = database.noteDao().idOf(vault, path)!!

    private suspend fun names(parent: String) = repository.children(parent).map { it.name }

    /** A vault that holds every kind of hidden file there is, and notes that point into them. */
    private suspend fun aVaultWithHiddenFiles() {
        excluded("uploads/", "node_modules/")
        index(
            "CLAUDE.md" to "# How this vault is written\n\n- [ ] keep notes short #conventions",
            "Learning/CLAUDE.md" to "# Conventions for learning notes\n\nsee [[Kafka]]",
            "Learning/AGENTS.md" to "# Conventions, for other agents",
            "Learning/Kafka.md" to
                "# Kafka\n\nthe log #streaming\n\n" +
                "rules in [[Learning/CLAUDE]], spec in [[uploads/Spec]]\n\n" +
                "![[diagram.png]]\n\n![[Spec]]",
            "Learning/MyCLAUDE.md" to "my own notes about Claude",
            "uploads/Spec.md" to "# Spec\n\nthe attached spec #streaming\n\n- [ ] read the spec 📅 2020-01-01",
        )
        file("uploads/diagram.png", BlobKind.IMAGE)
        file("uploads/manual.pdf", BlobKind.OTHER)
        file("Learning/chart.png", BlobKind.IMAGE)
    }

    @Test
    fun `hidden files are left out of the tree, the count and the folders they imply`() =
        runTest {
            aVaultWithHiddenFiles()

            // No uploads folder, although a note and two attachments imply one.
            assertThat(names("")).containsExactly("Learning")
            assertThat(names("Learning")).containsExactly("Kafka", "MyCLAUDE", "chart.png")
            assertThat(names("uploads")).isEmpty()
            // The reactive tree the browser draws, not only the one-off.
            assertThat(repository.childrenFlow("").first().map { it.name }).containsExactly("Learning")
            assertThat(repository.noteCount.first()).isEqualTo(2)
        }

    @Test
    fun `hidden files are not found by search, the switcher, tags or tasks`() =
        runTest {
            aVaultWithHiddenFiles()

            assertThat(repository.search("conventions")).isEmpty()
            assertThat(repository.search("spec").map { it.path }).containsExactly("Learning/Kafka.md")
            assertThat(repository.quickSwitch("claude").map { it.path }).containsExactly("Learning/MyCLAUDE.md")
            assertThat(repository.quickSwitch("spec")).isEmpty()
            val tags = repository.tagTree(vault).first().map { it.path }
            assertThat(tags).containsExactly("streaming")
            assertThat(repository.notesTagged(vault, "streaming").map { it.path }).containsExactly("Learning/Kafka.md")
            assertThat(repository.openTasks().first()).isEmpty()
            assertThat(repository.queryableTasks(vault).first()).isEmpty()
            // Nor in the counts a widget and the digest show.
            assertThat(repository.overdueCount("2026-10-27")).isEqualTo(0)
            assertThat(repository.dueToday("2020-01-01")).isEmpty()
            assertThat(repository.openTaskCounts().first()).isEmpty()
        }

    @Test
    fun `hidden files are not recent, random, a backlink or in the graph`() =
        runTest {
            aVaultWithHiddenFiles()
            val kafka = idOf("Learning/Kafka.md")
            val guide = idOf("Learning/CLAUDE.md")
            repository.markOpened(guide)
            repository.markOpened(kafka)

            assertThat(repository.recentlyOpened().first().map { it.path }).containsExactly("Learning/Kafka.md")
            repeat(20) {
                assertThat(repository.randomNote()!!.path).isAnyOf("Learning/Kafka.md", "Learning/MyCLAUDE.md")
            }
            // The guide links to Kafka; Kafka is not told so.
            assertThat(repository.backlinks(kafka)).isEmpty()
            assertThat(repository.backlinkCount(kafka)).isEqualTo(0)
            assertThat(repository.neighbours(kafka)!!.outgoing).isEmpty()
            val (graph, _) = repository.linkGraph(kafka)!!
            assertThat(graph.paths).containsExactly("Learning/Kafka.md", "Learning/MyCLAUDE.md")
        }

    @Test
    fun `a link and an embed into a hidden file still resolve`() =
        runTest {
            aVaultWithHiddenFiles()

            val kafka = repository.note(idOf("Learning/Kafka.md"))!!

            assertThat(kafka.linkTargets)
                .containsExactly(
                    "Learning/CLAUDE",
                    idOf("Learning/CLAUDE.md"),
                    "uploads/Spec",
                    idOf("uploads/Spec.md"),
                    "Spec",
                    idOf("uploads/Spec.md"),
                )
            assertThat(kafka.brokenTargets).isEmpty()
            // The picture is found in the excluded folder, not left as a guess.
            assertThat(kafka.blocks.filterIsInstance<MdBlock.Image>().map { it.path })
                .containsExactly("uploads/diagram.png")
        }

    @Test
    fun `a hidden note opens, with its tasks and its graph`() =
        runTest {
            aVaultWithHiddenFiles()
            val spec = idOf("uploads/Spec.md")

            val note = repository.note(spec)!!

            assertThat(note.title).isEqualTo("Spec")
            assertThat(repository.tasksIn(spec).map { it.text }).containsExactly("read the spec")
            val (graph, centre) = repository.linkGraph(spec)!!
            assertThat(centre).isAtLeast(0)
            assertThat(graph.paths[centre]).isEqualTo("uploads/Spec.md")
            assertThat(repository.find(vault, "Learning/CLAUDE")).isEqualTo(idOf("Learning/CLAUDE.md"))
        }

    @Test
    fun `the folder of a hidden note opened by a link can be browsed, without the note in it`() =
        runTest {
            aVaultWithHiddenFiles()

            // What a tap on [[Learning/CLAUDE]] and then the drawer's folder
            // button do: find the note, ask where it is, open its folder.
            val guide = repository.find(vault, "Learning/CLAUDE")!!
            val (inVault, path) = repository.locate(guide)!!
            val folder = path.substringBeforeLast('/', "")

            assertThat(inVault).isEqualTo(vault)
            assertThat(folder).isEqualTo("Learning")
            // The folder is there, as the browser draws it, and the note that
            // led to it is still not listed in it.
            assertThat(repository.childrenFlow(folder).first().map { it.name })
                .containsExactly("Kafka", "MyCLAUDE", "chart.png")
            // The one at the root opens the root.
            val root = repository.locate(idOf("CLAUDE.md"))!!.second.substringBeforeLast('/', "")
            assertThat(repository.childrenFlow(root).first().map { it.name }).containsExactly("Learning")
        }

    @Test
    fun `showing hidden files lists everything, and hiding them again puts it back`() =
        runTest {
            aVaultWithHiddenFiles()

            settings.setShowHiddenFiles(true)
            hidden.applyAll()

            assertThat(names("")).containsExactly("Learning", "uploads", "CLAUDE")
            assertThat(names("uploads")).containsExactly("Spec", "diagram.png", "manual.pdf")
            assertThat(repository.childrenFlow("uploads").first().map { it.name })
                .containsExactly("Spec", "diagram.png", "manual.pdf")
            assertThat(repository.search("conventions")).hasSize(3)
            assertThat(repository.noteCount.first()).isEqualTo(6)
            assertThat(repository.openTasks().first().map { it.text })
                .containsExactly("keep notes short #conventions", "read the spec")
            assertThat(repository.overdueCount("2026-10-27")).isEqualTo(1)

            settings.setShowHiddenFiles(false)
            hidden.applyAll()

            assertThat(names("")).containsExactly("Learning")
            assertThat(repository.search("conventions")).isEmpty()
        }

    @Test
    fun `the agents' files can be listed while the vault's excluded files stay out`() =
        runTest {
            aVaultWithHiddenFiles()

            settings.setHideAgentFiles(false)
            hidden.applyAll()

            assertThat(names("")).containsExactly("Learning", "CLAUDE")
            assertThat(names("Learning")).containsExactly("AGENTS", "CLAUDE", "Kafka", "MyCLAUDE", "chart.png")
            assertThat(names("uploads")).isEmpty()
        }

    @Test
    fun `a vault that excludes nothing hides only the agents' files`() =
        runTest {
            index("uploads/Spec.md" to "spec", "Learning/CLAUDE.md" to "rules", "Learning/Kafka.md" to "log")
            file("uploads/diagram.png", BlobKind.IMAGE)

            assertThat(names("")).containsExactly("Learning", "uploads")
            assertThat(names("uploads")).containsExactly("Spec", "diagram.png")
            assertThat(names("Learning")).containsExactly("Kafka")
        }

    @Test
    fun `excluded files that arrive after the notes hide them, and only the notes that are wrong are written`() =
        runTest {
            index("uploads/Spec.md" to "spec", "Learning/Kafka.md" to "log")
            assertThat(names("")).containsExactly("Learning", "uploads")

            // What a sync that brings a new app.json does after indexing.
            excluded("uploads")
            assertThat(hidden.apply(vault)).isEqualTo(1)

            assertThat(names("")).containsExactly("Learning")
            assertThat(hidden.apply(vault)).isEqualTo(0)
        }

    @Test
    fun `app settings that are not JSON hide nothing beyond the agents' files`() =
        runTest {
            files.write(vault, VaultFilter.APP, "{ not json".toByteArray())
            file(VaultFilter.APP, BlobKind.CONFIG)
            index("uploads/Spec.md" to "spec", "CLAUDE.md" to "rules")

            assertThat(names("")).containsExactly("uploads")
        }

    @Test
    fun `the rules follow the settings as a flow, for the tree's attachments`() =
        runTest {
            excluded("uploads/")

            assertThat(hidden.rules(vault).first().hides("uploads/diagram.png")).isTrue()
            settings.setShowHiddenFiles(true)
            assertThat(hidden.rules(vault).first().hides("uploads/diagram.png")).isFalse()
        }
}
