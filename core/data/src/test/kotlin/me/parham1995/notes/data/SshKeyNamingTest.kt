package me.parham1995.notes.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.SshKeyStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A key per repository, named by the vault's id -- and the keys an existing
 * install already has moved over to those names without being replaced.
 *
 * The second part is what stops an upgrade silently invalidating a deploy key
 * that is already registered on GitHub: the bytes must still be the ones the
 * existing install wrote.
 */
@RunWith(RobolectricTestRunner::class)
class SshKeyNamingTest {
    private val store = SshKeyStore(ApplicationProvider.getApplicationContext())

    private fun ssh(
        id: Long,
        name: String,
    ) = VaultEntity(id = id, owner = "o", repo = "r$id", name = name, transport = "SSH")

    @Test
    fun `every repository gets a file of its own, by id`() {
        assertThat(store.identity(1).name).isEqualTo("id_vault_1")
        assertThat(store.identity(2).name).isEqualTo("id_vault_2")
    }

    @Test
    fun `names that used to collide no longer share a key file`() {
        // By name, both Persian vaults were `id_____` and `Work`/`work` were
        // one file. By id they cannot be.
        val names = listOf(1L, 2L, 3L, 4L).map { store.identity(it).name }

        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `no key exists until one is generated`() {
        assertThat(store.exists(99)).isFalse()
        assertThat(store.publicKeyLine(99)).isNull()
        assertThat(store.fingerprint(99)).isEqualTo("no key")
    }

    @Test
    fun `an existing key moves to its vault's id and keeps its bytes`() {
        // What an install before this change has on disk for a vault named
        // "Documents": the key registered on GitHub.
        write("id_documents", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample registered")

        store.adoptLegacyNames(listOf(ssh(2, "Documents")))

        assertThat(store.exists(2)).isTrue()
        assertThat(store.publicKeyLine(2)).endsWith("registered")
        assertThat(File(sshDir, "id_documents").exists()).isFalse()
    }

    @Test
    fun `two vaults that shared one file leave it with the first`() {
        // Both Persian names collapsed to the same file; only one repository
        // can have that key registered, and the older vault is the bet.
        write("id_____", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample shared")
        val vaults = listOf(ssh(4, "کتاب"), ssh(3, "دفتر"))

        store.adoptLegacyNames(vaults)
        store.adoptLegacyNames(vaults)

        assertThat(store.publicKeyLine(3)).endsWith("shared")
        // The second generates its own, which it needed all along.
        assertThat(store.exists(4)).isFalse()
    }

    @Test
    fun `a vault that does not sync over SSH never takes a key`() {
        write("id_work", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample work")
        val rest = VaultEntity(id = 1, owner = "o", repo = "r", name = "Work", transport = "REST")

        store.adoptLegacyNames(listOf(rest, ssh(2, "work")))

        assertThat(store.exists(1)).isFalse()
        assertThat(store.publicKeyLine(2)).endsWith("work")
    }

    @Test
    fun `a vault that already has its own key keeps it`() {
        write("id_vault_2", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample current")
        write("id_documents", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample stale")

        store.adoptLegacyNames(listOf(ssh(2, "Documents")))

        assertThat(store.publicKeyLine(2)).endsWith("current")
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

        val claimed = setOf("id_notes")
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

            store.generate(7)
            val afterGenerate = store.revision.value
            assertThat(afterGenerate).isGreaterThan(before)

            store.deleteByFileName(store.identity(7).name)
            assertThat(store.revision.value).isGreaterThan(afterGenerate)
        }
}
