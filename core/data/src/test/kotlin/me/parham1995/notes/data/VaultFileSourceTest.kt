package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NotesDatabase
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A link in a note can name anything, including a path that climbs out of
 * its vault. Asking to open one is nothing to open, not a crash out of a tap.
 */
@RunWith(RobolectricTestRunner::class)
class VaultFileSourceTest {
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var source: VaultFileSource

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = testDatabase()
        files = VaultFileStore(context)
        source =
            VaultFileSource(
                files = files,
                blobs = database.blobDao(),
                vaults = database.vaultDao(),
                settings = SettingsStore(context),
                tokens = TokenStore(context),
                http = OkHttpClient(),
            )
    }

    @After
    fun tearDown() =
        runTest {
            database.close()
            files.clear()
        }

    @Test
    fun `a path out of the vault opens nothing and throws nothing`() =
        runTest {
            assertThat(files.holds(1, "a/../../x.pdf")).isFalse()
            assertThat(source.localFile(1, "a/../../x.pdf")).isNull()
            assertThat(source.bytes(1, "../../../etc/hosts")).isNull()
        }

    @Test
    fun `a file in the vault is still found`() =
        runTest {
            files.write(1, "uploads/scan.pdf", byteArrayOf(1, 2, 3))

            assertThat(files.holds(1, "uploads/scan.pdf")).isTrue()
            assertThat(source.localFile(1, "uploads/scan.pdf")?.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
            assertThat(source.bytes(1, "uploads/scan.pdf")).isEqualTo(byteArrayOf(1, 2, 3))
        }
}
