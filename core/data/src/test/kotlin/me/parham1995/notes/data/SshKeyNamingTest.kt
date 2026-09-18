package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.git.SshKeyStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A key per repository, and the root one keeping the name it has always had.
 *
 * The second part is what stops an upgrade silently invalidating a deploy key
 * that is already registered on GitHub: the file must still be the one the
 * existing install wrote.
 */
@RunWith(RobolectricTestRunner::class)
class SshKeyNamingTest {
    private val store = SshKeyStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `the root-mounted repository keeps the original file name`() {
        assertThat(store.identity("").name).isEqualTo("id_notes")
    }

    @Test
    fun `every other repository gets a file of its own`() {
        assertThat(store.identity("documents").name).isEqualTo("id_documents")
        assertThat(store.identity("Work").name).isEqualTo("id_work")
    }

    @Test
    fun `two repositories never share a key file`() {
        val names = listOf("", "documents", "Work", "archive").map { store.identity(it).name }

        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `a folder name that is awkward on a filesystem still yields a usable one`() {
        // Mounts are folder names, so they can carry spaces and non-Latin
        // script -- the vault this was built for has plenty of both.
        assertThat(store.identity("Code Chorus").name).isEqualTo("id_code_chorus")
        assertThat(store.identity("دفتر").name).isEqualTo("id_____")
    }

    @Test
    fun `no key exists until one is generated`() {
        assertThat(store.exists("never-made")).isFalse()
        assertThat(store.publicKeyLine("never-made")).isNull()
        assertThat(store.fingerprint("never-made")).isEqualTo("no key")
    }

    @Test
    fun `keys on disk are listed with what they are`() {
        write("id_notes", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample notes-droid")

        val stored = store.stored().single()

        assertThat(stored.fileName).isEqualTo("id_notes")
        assertThat(stored.algorithm).isEqualTo("ssh-ed25519")
    }

    @Test
    fun `a key no repository claims is still found`() {
        // What a removed repository leaves behind: nothing else would ever
        // mention it, and it is a private key sitting in the app's storage.
        write("id_notes", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample a")
        write("id_gone", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample b")

        val claimed = setOf(store.identity("").name)
        val orphans = store.stored().filterNot { it.fileName in claimed }

        assertThat(orphans.map { it.fileName }).containsExactly("id_gone")
    }

    @Test
    fun `deleting a key removes both halves`() {
        write("id_gone", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample b")
        assertThat(store.stored().map { it.fileName }).contains("id_gone")

        store.deleteByFileName("id_gone")

        assertThat(store.stored().map { it.fileName }).doesNotContain("id_gone")
        assertThat(File(sshDir, "id_gone").exists()).isFalse()
    }

    private val sshDir: File
        get() =
            File(
                ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
                "ssh",
            ).apply { mkdirs() }

    private fun write(
        name: String,
        line: String,
    ) {
        File(sshDir, name).writeText("private")
        File(sshDir, "$name.pub").writeText(line + "\n")
    }

    @Before
    fun clearKeys() {
        sshDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `creating or removing a key is announced`() =
        runTest {
            // Files have nothing to subscribe to, so anything listing keys has
            // to be told they changed. Without this the settings screen shows
            // no key for a repository that has just been given one.
            val before = store.revision.value

            store.generate("announced")
            val afterGenerate = store.revision.value
            assertThat(afterGenerate).isGreaterThan(before)

            store.deleteByFileName(store.identity("announced").name)
            assertThat(store.revision.value).isGreaterThan(afterGenerate)
        }
}
