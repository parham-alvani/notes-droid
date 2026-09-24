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
import kotlin.random.Random

/**
 * The operators search reads -- a phrase, an exclusion, a folder -- against a
 * real FTS5 index, and the promise that nothing typed can make it throw.
 *
 * Fixtures are synthetic; the repository is public.
 */
@RunWith(RobolectricTestRunner::class)
class SearchOperatorsTest {
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

    private suspend fun vault(
        id: Long,
        vararg notes: Pair<String, String>,
    ) {
        val entries =
            notes.map { (path, text) ->
                files.write(id, path, text.toByteArray())
                PathAndSha(path, "sha-" + (id to path).hashCode())
            }
        indexer.indexAll(id, entries)
    }

    private suspend fun seed() =
        vault(
            1L,
            "Garden/Tomatoes.md" to "The red harvest came early this year.",
            "Garden/Beds/Peppers.md" to "A harvest of red peppers, and one green.",
            "Kitchen/Sauce.md" to "Red sauce from the harvest, slowly.",
            "Kitchen/Bread.md" to "Harvest bread with seeds.",
        )

    private suspend fun titles(query: String) = search.search(1L, query).map { it.title }

    @Test
    fun `a quoted phrase matches the words together and in order`() =
        runTest {
            seed()
            // All four say "harvest", three say "red"; only one says "red harvest".
            assertThat(titles("\"red harvest\"")).containsExactly("Tomatoes")
        }

    @Test
    fun `a dash leaves out the notes that say the word`() =
        runTest {
            seed()
            assertThat(titles("harvest -red")).containsExactly("Bread")
            assertThat(titles("harvest -\"red peppers\"")).containsExactly("Tomatoes", "Sauce", "Bread")
        }

    @Test
    fun `a hyphen inside a word is not an exclusion`() =
        runTest {
            vault(1L, "Notes/Plan.md" to "The follow-up meeting.")
            assertThat(titles("follow-up")).containsExactly("Plan")
        }

    @Test
    fun `path narrows to a folder of this vault`() =
        runTest {
            seed()
            vault(2L, "Garden/Elsewhere.md" to "A harvest in another vault.")

            assertThat(titles("harvest path:Garden")).containsExactly("Tomatoes", "Peppers")
            assertThat(titles("harvest path:Garden/Beds")).containsExactly("Peppers")
            assertThat(titles("harvest -path:Garden")).containsExactly("Sauce", "Bread")
        }

    @Test
    fun `a folder on its own lists what is in it`() =
        runTest {
            seed()
            assertThat(titles("path:Kitchen")).containsExactly("Bread", "Sauce").inOrder()
            assertThat(titles("path:Kitchen -seeds")).containsExactly("Sauce")
        }

    @Test
    fun `a folder name is a prefix and not a pattern`() =
        runTest {
            vault(1L, "100%/Done.md" to "harvest", "100x/Other.md" to "harvest")
            assertThat(titles("path:100%")).containsExactly("Done")
        }

    @Test
    fun `plain words still match as a prefix while typing`() =
        runTest {
            seed()
            assertThat(titles("harv")).hasSize(4)
            assertThat(titles("\"red harv")).containsExactly("Tomatoes")
        }

    @Test
    fun `exclusions alone ask for nothing`() {
        assertThat(FtsQuery.parse("-red")).isNull()
        assertThat(FtsQuery.parse("-path:Garden")).isNull()
        assertThat(FtsQuery.parse("   ")).isNull()
        assertThat(FtsQuery.parse("\"\"")).isNull()
    }

    @Test
    fun `operators are recognised for what they are`() {
        assertThat(FtsQuery.usesOperators("harvest")).isFalse()
        assertThat(FtsQuery.usesOperators("follow-up")).isFalse()
        assertThat(FtsQuery.usesOperators("harvest -red")).isTrue()
        assertThat(FtsQuery.usesOperators("\"red harvest\"")).isTrue()
        assertThat(FtsQuery.usesOperators("path:Garden")).isTrue()
    }

    @Test
    fun `nothing typed makes the index throw`() =
        runTest {
            seed()
            val pieces =
                listOf(
                    "\"",
                    "'",
                    ":",
                    "-",
                    "*",
                    "^",
                    "(",
                    ")",
                    "path:",
                    "-path:",
                    "AND",
                    "OR",
                    "NOT",
                    "NEAR",
                    "red",
                    "harvest",
                    "Garden/",
                    " ",
                    " ",
                    "%",
                    "_",
                    "\\",
                    "{",
                    "}",
                    "+",
                    "\"\"",
                    "--",
                    "ک",
                    "۱",
                )
            val random = Random(1995)
            repeat(FUZZ_ROUNDS) {
                val query = (0 until random.nextInt(1, 8)).joinToString("") { pieces.random(random) }
                // Throwing is the failure; any answer at all is a pass.
                search.search(1L, query)
            }
        }

    private companion object {
        const val FUZZ_ROUNDS = 600
    }
}
