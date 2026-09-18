package me.parham1995.notes.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
 * The manifest is keyed by vault and path, not by path.
 *
 * Every vault names its files relative to itself, so the same name in two
 * vaults is two files. Keyed by path alone the second one silently replaced
 * the first, which is a repository quietly losing rows during a sync -- and it
 * made the migration to relative paths impossible to commit, which left the
 * database unopenable and the app unable to start at all.
 */
@RunWith(RobolectricTestRunner::class)
class BlobManifestTest {
    private lateinit var database: NotesDatabase

    private val notes = 1L
    private val documents = 2L

    @Before
    fun setUp() {
        database = testDatabase()
    }

    @After
    fun tearDown() = database.close()

    private fun blob(
        vaultId: Long,
        path: String,
        sha: String,
    ) = BlobEntity(
        path = path,
        vaultId = vaultId,
        sha = sha,
        size = 1,
        kind = BlobKind.IMAGE,
        localState = LocalState.DOWNLOADED,
    )

    @Test
    fun `two vaults each keep a file of the same name`() =
        runTest {
            val blobs = database.blobDao()
            blobs.upsertAll(
                listOf(
                    blob(notes, "uploads/logo.png", "a"),
                    blob(documents, "uploads/logo.png", "b"),
                ),
            )

            assertThat(blobs.byPath(notes, "uploads/logo.png")?.sha).isEqualTo("a")
            assertThat(blobs.byPath(documents, "uploads/logo.png")?.sha).isEqualTo("b")
        }

    @Test
    fun `changing one vault's copy leaves the other alone`() =
        runTest {
            val blobs = database.blobDao()
            blobs.upsert(blob(notes, "README.md", "a"))
            blobs.upsert(blob(documents, "README.md", "b"))

            blobs.upsert(blob(documents, "README.md", "changed"))

            assertThat(blobs.byPath(notes, "README.md")?.sha).isEqualTo("a")
            assertThat(blobs.byPath(documents, "README.md")?.sha).isEqualTo("changed")
        }

    @Test
    fun `deleting from one vault leaves the other alone`() =
        runTest {
            val blobs = database.blobDao()
            blobs.upsert(blob(notes, "LICENSE", "a"))
            blobs.upsert(blob(documents, "LICENSE", "b"))

            blobs.deleteByPath(documents, "LICENSE")

            assertThat(blobs.byPath(notes, "LICENSE")?.sha).isEqualTo("a")
            assertThat(blobs.byPath(documents, "LICENSE")).isNull()
            // The browser lists attachments from here, so a vault losing a row
            // to its neighbour is a file that disappears from the tree.
            assertThat(blobs.attachmentPaths(notes).first()).containsExactly("LICENSE")
        }
}
