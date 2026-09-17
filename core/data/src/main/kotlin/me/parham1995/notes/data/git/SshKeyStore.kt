package me.parham1995.notes.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SSH key the git transport authenticates with.
 *
 * The key is generated **on the device** and the private half never leaves it:
 * what gets copied out is the public line, which is pasted into GitHub as a
 * read-only deploy key. That is the whole appeal of this transport over a
 * token -- nothing secret has to be moved between machines, and a deploy key
 * cannot reach any other repository.
 */
@Singleton
class SshKeyStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val sshDir = File(context.filesDir, "ssh").apply { mkdirs() }
        private val privateKey = File(sshDir, "id_notes")
        private val publicKey = File(sshDir, "id_notes.pub")

        val directory: File get() = sshDir

        val identity: File get() = privateKey

        fun exists(): Boolean = privateKey.isFile && publicKey.isFile

        /** The line to paste into GitHub's deploy-key box. */
        fun publicKeyLine(): String? = publicKey.takeIf { it.isFile }?.readText()?.trim()

        suspend fun generate(comment: String = "notes-droid"): String =
            withContext(Dispatchers.IO) {
                val generated = SshKeyGenerator.generate()
                val pair = generated.pair

                privateKey.outputStream().use { out ->
                    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, comment, null, out)
                }
                // ssh refuses a world-readable identity file.
                privateKey.setReadable(false, false)
                privateKey.setReadable(true, true)
                privateKey.setWritable(false, false)
                privateKey.setWritable(true, true)

                val line = PublicKeyEntry.toString(pair.public) + " " + comment
                publicKey.writeText(line + "\n")
                line
            }

        fun delete() {
            privateKey.delete()
            publicKey.delete()
        }
    }
