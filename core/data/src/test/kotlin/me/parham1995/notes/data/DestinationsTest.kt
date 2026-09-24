package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.sync.VaultFilter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Notes asked for from outside a list: today's, so far. Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class DestinationsTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var indexer: VaultIndexer
    private lateinit var settings: SettingsStore
    private lateinit var destinations: Destinations

    private val day = LocalDate.of(2026, 3, 4)

    @Before
    fun setUp() {
        database = testDatabase()
        files = VaultFileStore(ApplicationProvider.getApplicationContext())
        settings = SettingsStore(ApplicationProvider.getApplicationContext())
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
        val repository =
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
            )
        val configs =
            ObsidianConfigStore(
                files = files,
                notes = database.noteDao(),
                syncState = database.syncStateDao(),
                log = SyncLog(database.syncLogDao()),
            )
        destinations = Destinations(repository, configs)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun vault(
        id: Long,
        vararg paths: String,
    ) {
        database.vaultDao().insert(VaultEntity(id = id, owner = "someone", repo = "repo-$id", name = "v$id"))
        val entries =
            paths.map { path ->
                files.write(id, path, "text".toByteArray())
                PathAndSha(path, "sha-" + (id to path).hashCode())
            }
        indexer.indexAll(id, entries)
    }

    @Test
    fun `today's note is found where the plugin's settings put it`() =
        runTest {
            vault(1L, "Journal/2026/03-04.md", "2026-03-04.md")
            files.write(1L, VaultFilter.DAILY_NOTES, """{"folder":"Journal","format":"YYYY/MM-DD"}""".toByteArray())
            settings.setActiveVault(1L)

            val found = destinations.dailyNote(day)

            assertThat(found)
                .isEqualTo(Destination.Note(1L, database.noteDao().idOf(1L, "Journal/2026/03-04.md")!!))
        }

    @Test
    fun `with no settings it is the plugin's default name at the root`() =
        runTest {
            vault(1L, "2026-03-04.md")
            settings.setActiveVault(1L)

            assertThat(destinations.dailyNote(day))
                .isEqualTo(Destination.Note(1L, database.noteDao().idOf(1L, "2026-03-04.md")!!))
        }

    @Test
    fun `a day without a note says where it would be, and makes nothing`() =
        runTest {
            vault(1L, "Other.md")
            settings.setActiveVault(1L)

            assertThat(destinations.dailyNote(day)).isEqualTo(Destination.NoDailyNote(1L, "2026-03-04.md"))
            assertThat(database.noteDao().byPath(1L, "2026-03-04.md")).isNull()
        }

    @Test
    fun `it is the vault being read that is looked in`() =
        runTest {
            // The first vault has today's note; the second, being read, does not.
            vault(1L, "2026-03-04.md")
            vault(2L, "Other.md")
            settings.setActiveVault(2L)

            assertThat(destinations.dailyNote(day)).isEqualTo(Destination.NoDailyNote(2L, "2026-03-04.md"))
        }
}
