package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.BlobEntity
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A base over a real index: the notes it lists come from one vault, their
 * properties from the files, their backlinks from the links table.
 *
 * Every fixture here is made up. The repository is public; the vault is not.
 */
@RunWith(RobolectricTestRunner::class)
class BaseRepositoryTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var indexer: VaultIndexer
    private lateinit var bases: BaseRepository

    @Before
    fun setUp() {
        database = testDatabase()
        files = VaultFileStore(ApplicationProvider.getApplicationContext())
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
        bases =
            BaseRepository(
                notes = database.noteDao(),
                links = database.linkDao(),
                tags = database.tagDao(),
                aliases = database.aliasDao(),
                blobs = database.blobDao(),
                files = files,
                log = SyncLog(database.syncLogDao()),
            )
    }

    @After
    fun tearDown() {
        database.close()
        files.root.deleteRecursively()
    }

    private suspend fun note(
        vaultId: Long,
        path: String,
        text: String,
    ): PathAndSha {
        files.write(vaultId, path, text.toByteArray())
        database.blobDao().upsert(
            BlobEntity(path, vaultId, "sha-$path", text.length.toLong(), BlobKind.MARKDOWN, LocalState.DOWNLOADED),
        )
        return PathAndSha(path, "sha-$path")
    }

    private suspend fun config(
        vaultId: Long,
        path: String,
        text: String,
    ) {
        files.write(vaultId, path, text.toByteArray())
        database.blobDao().upsert(
            BlobEntity(path, vaultId, "sha-$path", text.length.toLong(), BlobKind.CONFIG, LocalState.DOWNLOADED),
        )
    }

    @Test
    fun `a base lists one vault's notes with their properties and backlinks`() =
        runTest {
            indexer.indexAll(
                1L,
                listOf(
                    note(1L, "Shops/Bakery.md", "---\nstage: open\nrating: \"4\"\n---\nBread."),
                    note(1L, "Shops/Cafe.md", "---\nstage: closed\n---\nCoffee, near [[Bakery]]."),
                    note(1L, "Home.md", "[[Bakery]] #home"),
                ),
            )
            // Another vault with a note in the same folder, which must not show.
            indexer.indexAll(2L, listOf(note(2L, "Shops/Elsewhere.md", "---\nstage: open\n---\n")))
            config(
                1L,
                "Shops/Shops.base",
                """
                filters: file.inFolder("Shops")
                formulas:
                  cited: file.backlinks.length
                views:
                  - type: table
                    name: Open
                    filters: stage == "open"
                    order: [file.name, stage, formula.cited]
                """.trimIndent(),
            )
            config(1L, ".obsidian/types.json", """{"types": {"rating": "number"}}""")

            val answer = bases.runFile(1L, "Shops.base", "Home.md")!!

            assertThat(answer.path).isEqualTo("Shops/Shops.base")
            val table = answer.result.tables.single()
            assertThat(
                table.groups
                    .single()
                    .rows
                    .map { it.path },
            ).containsExactly("Shops/Bakery.md")
            assertThat(
                table.groups
                    .single()
                    .rows
                    .single()
                    .cells
                    .map { it.text },
            ).containsExactly("Bakery", "open", "2")
                .inOrder()
            assertThat(answer.noteIds).containsKey("Shops/Bakery.md")
            assertThat(answer.result.problems).isEmpty()
        }

    @Test
    fun `a property type from the vault decides how a value reads`() =
        runTest {
            indexer.indexAll(1L, listOf(note(1L, "A.md", "---\ncode: 2026\n---\n")))
            val base = "views:\n  - type: table\n    filters: code == \"2026\""
            // Read by its shape, 2026 is a number and not the text "2026".
            assertThat(
                bases
                    .run(1L, base, "A.md")
                    .result.tables
                    .single()
                    .count,
            ).isEqualTo(0)

            config(1L, ".obsidian/types.json", """{"types": {"code": "text"}}""")
            assertThat(
                bases
                    .run(1L, base, "A.md")
                    .result.tables
                    .single()
                    .count,
            ).isEqualTo(1)
        }

    @Test
    fun `a base that is not there is nothing`() =
        runTest {
            assertThat(bases.runFile(1L, "Missing.base", "Home.md")).isNull()
        }

    @Test
    fun `only the front matter of a note is read`() {
        val file =
            kotlin.io.path
                .createTempFile()
                .toFile()
        file.writeText("---\na: 1\n---\nbody\n---\nnot: this\n")
        assertThat(VaultBaseIndex.frontMatterOf(file)).isEqualTo("---\na: 1\n---\n")
        file.writeText("no front matter\n---\n")
        assertThat(VaultBaseIndex.frontMatterOf(file)).isEmpty()
        file.delete()
    }
}
