package me.parham1995.notes.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Search that does not care which keyboard wrote a Persian word.
 *
 * Letters are spelled as escapes, because the Arabic and Persian forms of a
 * letter look the same -- which is the bug. Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class PersianSearchTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var indexer: VaultIndexer
    private lateinit var search: SearchIndex
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        database = testDatabase()
        wire(database)
        // The quick switcher answers for the active vault, which lives in
        // settings that outlast a test: another class left it on vault 2, and
        // these failed only when the whole suite ran.
        runBlocking { SettingsStore(ApplicationProvider.getApplicationContext()).setActiveVault(1L) }
    }

    private fun wire(database: NotesDatabase) {
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

    private suspend fun vault(
        id: Long,
        vararg notes: Pair<String, String>,
    ) {
        if (database.vaultDao().byId(id) == null) {
            database.vaultDao().insert(VaultEntity(id = id, owner = "someone", repo = "repo-$id", name = "v$id"))
        }
        val entries =
            notes.map { (path, text) ->
                files.write(id, path, text.toByteArray())
                PathAndSha(path, "sha-" + (id to path).hashCode())
            }
        indexer.indexAll(id, entries)
    }

    private suspend fun titles(query: String) = search.search(1L, query).map { it.title }

    @Test
    fun `a word written with the arabic kaf is found by the persian one`() =
        runTest {
            vault(1L, "Arabic.md" to "این $KAR_ARABIC است", "Other.md" to "nothing")

            assertThat(titles(KAR_PERSIAN)).containsExactly("Arabic")
        }

    @Test
    fun `and the other way round`() =
        runTest {
            vault(1L, "Persian.md" to "این $KAR_PERSIAN است")

            assertThat(titles(KAR_ARABIC)).containsExactly("Persian")
        }

    @Test
    fun `the arabic yeh is the persian yeh`() =
        runTest {
            vault(1L, "Yeh.md" to "بازي خوب")

            assertThat(titles("بازی")).containsExactly("Yeh")
        }

    @Test
    fun `a half-space is not a word break`() =
        runTest {
            vault(
                1L,
                "WithHalfSpace.md" to "من $MIKHAHAM_ZWNJ بروم",
                "WithoutHalfSpace.md" to "او $MIKHAHAM_JOINED برود",
            )

            assertThat(titles(MIKHAHAM_JOINED)).containsExactly("WithHalfSpace", "WithoutHalfSpace")
            assertThat(titles(MIKHAHAM_ZWNJ)).containsExactly("WithHalfSpace", "WithoutHalfSpace")
        }

    @Test
    fun `persian digits find ascii ones and back`() =
        runTest {
            vault(1L, "Ascii.md" to "budget for 1404", "Persian.md" to "بودجه $YEAR_PERSIAN")

            assertThat(titles(YEAR_PERSIAN)).containsExactly("Ascii", "Persian")
            assertThat(titles("1404")).containsExactly("Ascii", "Persian")
        }

    @Test
    fun `the excerpt shows the characters the note was written in`() =
        runTest {
            vault(1L, "Arabic.md" to "این $KAR_ARABIC را $MIKHAHAM_ZWNJ")

            val hit = search.search(1L, KAR_PERSIAN).single()

            assertThat(hit.snippet).contains("[$KAR_ARABIC]")
            assertThat(hit.snippet).contains(MIKHAHAM_ZWNJ)
            assertThat(hit.snippet).doesNotContain(KAR_PERSIAN)
        }

    @Test
    fun `an english excerpt reads as it always did`() =
        runTest {
            vault(1L, "Plain.md" to "The red harvest came early this year.")

            assertThat(search.search(1L, "harvest").single().snippet)
                .isEqualTo("The red [harvest] came early this year.")
        }

    @Test
    fun `phrases and exclusions fold letter forms too`() =
        runTest {
            vault(
                1L,
                "Good.md" to "$KAR_ARABIC خوب",
                "Other.md" to "$KAR_PERSIAN بد",
            )

            assertThat(titles("\"$KAR_PERSIAN خوب\"")).containsExactly("Good")
            assertThat(titles("$KAR_PERSIAN -خوب")).containsExactly("Other")
        }

    @Test
    fun `a folder named in arabic letters is found by persian ones`() =
        runTest {
            vault(
                1L,
                "$BOOKS_ARABIC/One.md" to "harvest",
                "Elsewhere/Two.md" to "harvest",
            )

            assertThat(titles("harvest path:$BOOKS_PERSIAN")).containsExactly("One")
            assertThat(titles("path:$BOOKS_PERSIAN")).containsExactly("One")
            assertThat(titles("harvest -path:$BOOKS_PERSIAN")).containsExactly("Two")
        }

    @Test
    fun `another vault is not searched`() =
        runTest {
            vault(1L, "Mine.md" to KAR_ARABIC)
            vault(2L, "Theirs.md" to KAR_ARABIC, "$BOOKS_ARABIC/$KAR_ARABIC.md" to "x")

            assertThat(titles(KAR_PERSIAN)).containsExactly("Mine")
            assertThat(repository.quickSwitch(KAR_PERSIAN)).isEmpty()
        }

    @Test
    fun `the quick switcher finds a name written in arabic letters`() =
        runTest {
            vault(1L, "Notes/$KAR_ARABIC Plan.md" to "x", "Notes/Unrelated.md" to "y")

            assertThat(repository.quickSwitch(KAR_PERSIAN).map { it.name }).containsExactly("$KAR_ARABIC Plan")
            assertThat(repository.quickSwitch("$KAR_PERSIAN pl").map { it.name })
                .containsExactly("$KAR_ARABIC Plan")
        }

    @Test
    fun `the quick switcher finds an alias written in arabic letters`() =
        runTest {
            vault(1L, "People/Someone.md" to "---\naliases: [$KAR_ARABIC]\n---\n", "Else.md" to "y")

            assertThat(repository.quickSwitch(KAR_PERSIAN).map { it.name }).containsExactly("Someone")
        }

    @Test
    fun `a mention written in the other letters is still a mention`() =
        runTest {
            vault(1L, "$KAR_PERSIAN.md" to "the note", "Source.md" to "این $KAR_ARABIC")
            val target = database.noteDao().idOf(1L, "$KAR_PERSIAN.md")!!

            val mentions = search.mentions(1L, KAR_PERSIAN, exclude = setOf(target))

            assertThat(mentions.map { it.title }).containsExactly("Source")
        }

    @Test
    fun `an index built before folding still answers until it is rebuilt`() =
        runTest {
            val file = File.createTempFile("notes", ".db").also { it.delete() }
            database.close()
            database = testDatabase(file)
            wire(database)
            vault(1L, "Kitchen/Harvest Bread.md" to "Harvest bread with seeds.", "Else.md" to "nothing")
            database.close()

            // What an install from before this change has: two columns of raw
            // text, and a row for a note that no longer exists.
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL("DROP TABLE note_fts")
                connection.execSQL(
                    "CREATE VIRTUAL TABLE note_fts USING fts5(title, body, " +
                        "tokenize = 'unicode61 remove_diacritics 2', prefix = '2 3', columnsize = 0)",
                )
                connection.execSQL(
                    "INSERT INTO note_fts(rowid, title, body) " +
                        "SELECT id, title, 'Harvest bread with seeds.' FROM notes WHERE path LIKE 'Kitchen/%'",
                )
                connection.execSQL("INSERT INTO note_fts(rowid, title, body) VALUES (999, 'Gone', 'seeds')")
            }

            database = testDatabase(file)
            wire(database)

            assertThat(titles("seeds")).containsExactly("Harvest Bread")
            assertThat(titles("seeds path:Kitchen")).containsExactly("Harvest Bread")
            assertThat(repository.quickSwitch("harvest").map { it.name }).containsExactly("Harvest Bread")
            file.delete()
        }

    private companion object {
        const val KAR_ARABIC = "كار"
        const val KAR_PERSIAN = "کار"
        const val MIKHAHAM_ZWNJ = "می‌خواهم"
        const val MIKHAHAM_JOINED = "میخواهم"
        const val YEAR_PERSIAN = "۱۴۰۴"
        const val BOOKS_ARABIC = "كتابها"
        const val BOOKS_PERSIAN = "کتابها"
    }
}
