package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.VaultEntry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Where synced bytes land, and what happens to a copy that went out of date. */
@RunWith(RobolectricTestRunner::class)
class RoomVaultSinkTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var sink: RoomVaultSink

    private val vaultId = 1L

    @Before
    fun setUp() {
        database = testDatabase()
        files = VaultFileStore(ApplicationProvider.getApplicationContext())
        sink = RoomVaultSink(files, database.blobDao()).also { it.vaultId = vaultId }
    }

    @After
    fun tearDown() =
        runTest {
            database.close()
            files.clear()
        }

    @Test
    fun `an attachment that changed upstream is not served from the old copy`() =
        runTest {
            // Opened once, so it is on disk.
            val path = "uploads/contract.pdf"
            files.write(vaultId, path, "version one".toByteArray())

            // Changed upstream: recorded as absent at its new sha.
            sink.record(VaultEntry(path, "sha-2", 11, BlobKind.OTHER), LocalState.ABSENT)

            // Opening it reads the disk first, so the old bytes must be gone
            // for the new ones ever to be fetched.
            assertThat(files.read(vaultId, path)).isNull()
            assertThat(database.blobDao().byPath(vaultId, path)!!.localState).isEqualTo(LocalState.ABSENT)
        }
}
