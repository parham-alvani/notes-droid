package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.git.SshKeyStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
