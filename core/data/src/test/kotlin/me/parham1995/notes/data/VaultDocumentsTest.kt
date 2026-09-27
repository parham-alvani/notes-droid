package me.parham1995.notes.data

import android.content.Context
import android.database.Cursor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.VaultEntity
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException

/**
 * The vaults as the system file picker sees them. The document id comes from
 * outside the app, so most of this is about what it must not reach: another
 * vault, a removed one, the app's own storage, a repository's `.git`.
 * Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class VaultDocumentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var documents: VaultDocuments
    private val lock = AppLock()
    private val settings = SettingsStore(context)

    @Before
    fun setUp() {
        database = testDatabase()
        files = VaultFileStore(context)
        runBlocking { files.clear() }
        documents = VaultDocuments(context, files, database.vaultDao(), database.noteDao(), lock, settings)
        runBlocking {
            vault(1L, "Garden")
            vault(2L, "Workshop")
            files.write(1L, "README.md", "garden".toByteArray())
            files.write(1L, "Plants/Tomato.md", "red".toByteArray())
            files.write(1L, "uploads/leaf.png", byteArrayOf(1, 2, 3))
            files.write(1L, ".obsidian/app.json", "{}".toByteArray())
            files.write(1L, ".git/config", "[core]".toByteArray())
            files.write(2L, "README.md", "workshop".toByteArray())
        }
    }

    @After
    fun tearDown() {
        database.close()
        // Settings outlive a test; a lock left on would fail the next class.
        runBlocking {
            settings.setAppLock(false)
            files.clear()
        }
    }

    private suspend fun vault(
        id: Long,
        name: String,
    ) {
        database.vaultDao().insert(VaultEntity(id = id, owner = "someone", repo = "repo-$id", name = name))
    }

    private fun Cursor.strings(column: String): List<String> =
        use {
            val index = getColumnIndexOrThrow(column)
            buildList { while (moveToNext()) add(getString(index)) }
        }

    @Test
    fun `every synced vault is a root of its own`() =
        runTest {
            // Added, never synced: nothing on disk to offer.
            vault(3L, "Empty")

            assertThat(documents.roots(null).strings(Root.COLUMN_SUMMARY)).containsExactly("Garden", "Workshop")
            assertThat(documents.roots(null).strings(Root.COLUMN_DOCUMENT_ID)).containsExactly("1:", "2:")
        }

    @Test
    fun `a folder lists folders first and hides the repository machinery`() {
        val children = documents.children("1:", null)

        assertThat(children.strings(Document.COLUMN_DOCUMENT_ID))
            .containsExactly("1:Plants", "1:uploads", "1:README.md")
            .inOrder()
    }

    @Test
    fun `a nested folder is listed with its full path`() {
        assertThat(documents.children("1:Plants", null).strings(Document.COLUMN_DOCUMENT_ID))
            .containsExactly("1:Plants/Tomato.md")
    }

    @Test
    fun `a document says what it is`() {
        documents.document("1:Plants/Tomato.md", null).use {
            it.moveToFirst()
            assertThat(it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))).isEqualTo("Tomato.md")
            assertThat(it.getString(it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE))).isEqualTo("text/markdown")
            assertThat(it.getLong(it.getColumnIndexOrThrow(Document.COLUMN_SIZE))).isEqualTo(3L)
        }
        documents.document("1:", null).use {
            it.moveToFirst()
            assertThat(it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))).isEqualTo("Garden")
            assertThat(it.getString(it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)))
                .isEqualTo(Document.MIME_TYPE_DIR)
        }
    }

    @Test
    fun `two vaults each open their own file of the same name`() {
        assertThat(documents.open("1:README.md").readText()).isEqualTo("garden")
        assertThat(documents.open("2:README.md").readText()).isEqualTo("workshop")
    }

    @Test
    fun `an id cannot climb out of its vault`() {
        assertThrows(FileNotFoundException::class.java) { documents.open("1:../2/README.md") }
        assertThrows(FileNotFoundException::class.java) { documents.open("1:../../shared_prefs/x.xml") }
        assertThrows(FileNotFoundException::class.java) { documents.children("1:..", null) }
    }

    @Test
    fun `nothing hidden can be opened by naming it`() {
        assertThrows(FileNotFoundException::class.java) { documents.open("1:.git/config") }
        assertThrows(FileNotFoundException::class.java) { documents.children("1:.obsidian", null) }
    }

    @Test
    fun `a link out of the vault is neither listed nor searched through`() =
        runTest {
            val outside =
                kotlin.io.path
                    .createTempDirectory("outside")
                    .toFile()
            java.io.File(outside, "Secret.md").writeText("x")
            java.nio.file.Files
                .createSymbolicLink(files.fileFor(1L, "Away").toPath(), outside.toPath())

            assertThat(documents.children("1:", null).strings(Document.COLUMN_DOCUMENT_ID)).doesNotContain("1:Away")
            assertThat(documents.search("1", "secret", null).strings(Document.COLUMN_DOCUMENT_ID)).isEmpty()
            assertThrows(FileNotFoundException::class.java) { documents.open("1:Away/Secret.md") }
            outside.deleteRecursively()
        }

    private suspend fun opened(
        vaultId: Long,
        path: String,
        at: Long,
    ) {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        val id =
            database.noteDao().upsert(
                NoteEntity(
                    vaultId = vaultId,
                    path = path,
                    parent = path.substringBeforeLast('/', ""),
                    name = name,
                    slug = name.lowercase(),
                    title = name,
                    blobSha = "sha",
                    size = 1,
                    isFolderNote = false,
                    isRtl = false,
                    hasMermaid = false,
                    hasMath = false,
                    indexedAt = 0,
                ),
            )
        database.noteDao().markOpened(id, at)
    }

    @Test
    fun `recent is what was read last in that vault, newest first`() =
        runTest {
            opened(1L, "README.md", at = 10)
            opened(1L, "Plants/Tomato.md", at = 20)
            opened(1L, "Gone.md", at = 30)
            opened(2L, "README.md", at = 40)

            assertThat(documents.recents("1", null).strings(Document.COLUMN_DOCUMENT_ID))
                .containsExactly("1:Plants/Tomato.md", "1:README.md")
                .inOrder()
        }

    @Test
    fun `only an image offers a thumbnail`() {
        fun flags(id: String) =
            documents.document(id, null).use {
                it.moveToFirst()
                it.getInt(it.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
            }

        assertThat(flags("1:uploads/leaf.png") and Document.FLAG_SUPPORTS_THUMBNAIL).isNotEqualTo(0)
        assertThat(flags("1:README.md") and Document.FLAG_SUPPORTS_THUMBNAIL).isEqualTo(0)
        assertThrows(FileNotFoundException::class.java) { documents.thumbnail("1:README.md", 64, 64) }
    }

    @Test
    fun `while the app is locked other apps see no vaults and cannot open one`() =
        runTest {
            settings.setAppLock(true)

            assertThat(documents.roots(null).count).isEqualTo(0)
            // A link handed out before the lock closed is refused too.
            assertThrows(FileNotFoundException::class.java) { documents.open("1:README.md") }
            assertThrows(FileNotFoundException::class.java) { documents.search("1", "tomato", null) }

            // Locked, but asked to leave the picker alone.
            settings.setLockPicker(false)
            assertThat(documents.roots(null).count).isEqualTo(2)
            settings.setLockPicker(true)

            lock.unlock()
            assertThat(documents.open("1:README.md").readText()).isEqualTo("garden")
        }

    @Test
    fun `a removed vault is not served even while its files linger`() =
        runTest {
            database.vaultDao().delete(2L)

            assertThrows(FileNotFoundException::class.java) { documents.open("2:README.md") }
            assertThat(documents.roots(null).strings(Root.COLUMN_ROOT_ID)).containsExactly("1")
        }

    @Test
    fun `malformed ids are refused rather than guessed at`() {
        assertThrows(FileNotFoundException::class.java) { documents.open("README.md") }
        assertThrows(FileNotFoundException::class.java) { documents.open("x:README.md") }
        assertThrows(FileNotFoundException::class.java) { documents.open("1:Plants") }
        assertThrows(FileNotFoundException::class.java) { documents.open("1:Missing.md") }
    }

    @Test
    fun `a child is only a child inside the same vault`() {
        assertThat(documents.isChild("1:", "1:Plants/Tomato.md")).isTrue()
        assertThat(documents.isChild("1:Plants", "1:Plants/Tomato.md")).isTrue()
        assertThat(documents.isChild("1:Plants", "1:PlantsExtra/x.md")).isFalse()
        assertThat(documents.isChild("1:", "2:README.md")).isFalse()
        assertThat(documents.isChild("1:Plants", "1:Plants")).isFalse()
    }

    @Test
    fun `search matches names in one vault and folds arabic letters`() =
        runTest {
            files.write(1L, "Notes/كار.md", "x".toByteArray())
            files.write(2L, "Tomato.md", "other vault".toByteArray())

            assertThat(documents.search("1", "tomato", null).strings(Document.COLUMN_DOCUMENT_ID))
                .containsExactly("1:Plants/Tomato.md")
            assertThat(documents.search("1", "کار", null).strings(Document.COLUMN_DOCUMENT_ID))
                .containsExactly("1:Notes/كار.md")
            assertThat(documents.search("1", "config", null).strings(Document.COLUMN_DOCUMENT_ID)).isEmpty()
        }
}
