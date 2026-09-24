package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.obsidian.Bookmark
import me.parham1995.notes.obsidian.DailyNotes
import me.parham1995.notes.sync.VaultFilter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Obsidian's own settings, read from where a sync leaves them, one vault at a
 * time. Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ObsidianConfigStoreTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var indexer: VaultIndexer
    private lateinit var store: ObsidianConfigStore

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
            )
        store =
            ObsidianConfigStore(
                files = files,
                notes = database.noteDao(),
                syncState = database.syncStateDao(),
                log = SyncLog(database.syncLogDao()),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun note(
        vaultId: Long,
        vararg paths: String,
    ) {
        val entries =
            paths.map { path ->
                files.write(vaultId, path, "text".toByteArray())
                PathAndSha(path, "sha-" + (vaultId to path).hashCode())
            }
        indexer.indexAll(vaultId, entries)
    }

    private suspend fun config(
        vaultId: Long,
        path: String,
        text: String,
    ) = files.write(vaultId, path, text.toByteArray())

    @Test
    fun `each vault has its own bookmarks, looked up in that vault`() =
        runTest {
            // Both vaults hold a Plan.md; each bookmark means its own.
            note(1L, "Plan.md")
            note(2L, "Plan.md", "Other.md")
            config(1L, VaultFilter.BOOKMARKS, """{"items":[{"type":"file","path":"Plan.md"}]}""")
            config(
                2L,
                VaultFilter.BOOKMARKS,
                """{"items":[{"type":"group","title":"G","items":[{"type":"file","path":"Other.md"}]}]}""",
            )

            val first = store.bookmarks(1L)
            val second = store.bookmarks(2L)

            assertThat(first.items).containsExactly(Bookmark.File("Plan.md"))
            assertThat(first.noteIds.getValue("Plan.md"))
                .isEqualTo(database.noteDao().idOf(1L, "Plan.md"))
            assertThat(second.noteIds.keys).containsExactly("Other.md")
            assertThat(second.noteIds.getValue("Other.md"))
                .isEqualTo(database.noteDao().idOf(2L, "Other.md"))
        }

    @Test
    fun `a bookmark to a note that is not here is kept, without an id`() =
        runTest {
            config(1L, VaultFilter.BOOKMARKS, """{"items":[{"type":"file","path":"Gone.md"}]}""")

            val found = store.bookmarks(flowOf(1L)).first()

            assertThat(found.items).containsExactly(Bookmark.File("Gone.md"))
            assertThat(found.noteIds).isEmpty()
        }

    @Test
    fun `no file and a broken file are both no bookmarks`() =
        runTest {
            assertThat(store.bookmarks(1L).items).isEmpty()
            config(1L, VaultFilter.BOOKMARKS, "{ not json")
            assertThat(store.bookmarks(1L).items).isEmpty()
        }

    @Test
    fun `daily notes settings are per vault, and default when absent`() =
        runTest {
            config(1L, VaultFilter.DAILY_NOTES, """{"folder":"Journal","format":"YYYY/MM/DD"}""")

            assertThat(store.dailyNotes(1L)).isEqualTo(DailyNotes("Journal", "YYYY/MM/DD"))
            assertThat(store.dailyNotes(2L)).isEqualTo(DailyNotes())
        }
}
