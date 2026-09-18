package me.parham1995.notes.data.git

import com.google.common.truth.Truth.assertThat
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Writing a key is not the same as writing a key something can read back.
 *
 * The original test only checked that the file began with the OpenSSH header,
 * which a malformed key would also do. A key sshd cannot load means
 * authentication fails, and GitHub answers a failed authentication by closing
 * the connection -- which surfaces as "remote hung up unexpectedly" with no
 * transfer and nothing else to go on.
 */
class SshKeyRoundTripTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a written private key loads back and matches`() {
        val generated = SshKeyGenerator.generate()
        val file = File(temp.root, "id_notes")

        file.outputStream().use { out ->
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(generated.pair, "notes-droid", null, out)
        }

        val loaded =
            file.inputStream().use { input ->
                SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(file.name), input, null)
            }

        assertThat(loaded).isNotNull()
        val roundTripped = loaded.first()
        assertThat(roundTripped.public).isEqualTo(generated.pair.public)
        assertThat(roundTripped.private).isEqualTo(generated.pair.private)
    }
}
