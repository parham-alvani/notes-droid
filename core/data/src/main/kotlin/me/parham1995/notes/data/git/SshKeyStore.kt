package me.parham1995.notes.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SSH keys the git transport authenticates with -- one per repository.
 *
 * A key is generated **on the device** and the private half never leaves it:
 * what gets copied out is the public line, which is pasted into GitHub as a
 * read-only deploy key. That is the whole appeal of this transport over a
 * token -- nothing secret has to be moved between machines, and a deploy key
 * cannot reach any other repository.
 *
 * One key per repository, because GitHub allows a deploy key on exactly one
 * repository: registering the same public line a second time is refused with
 * "key is already in use". The alternative -- a key on the account rather than
 * the repository -- would be one key, and would also grant write access to
 * everything the account can reach, which is a poor trade for a reader.
 *
 * Keys are named after the mount so that the vault mounted at the root keeps
 * the file it already had, and an existing install does not have to register
 * anything again.
 */
@Singleton
class SshKeyStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        init {
            // sshd classes load as soon as a key is generated, which happens in
            // settings long before any transport exists. Its static
            // initialisers throw on Android unless this has already run, and a
            // poisoned class stays poisoned for the life of the process.
            AndroidGitEnvironment.install(File(context.filesDir, "git"))
        }

        private val sshDir = File(context.filesDir, "ssh").apply { mkdirs() }

        val directory: File get() = sshDir

        /**
         * `id_notes` for the root-mounted vault -- the name it has always had,
         * so the key already registered on GitHub keeps working -- and a name
         * derived from the mount for every other repository.
         */
        private fun baseName(mount: String): String =
            if (mount.isEmpty()) "id_notes" else "id_" + mount.lowercase().replace(UNSAFE, "_")

        fun identity(mount: String = ""): File = File(sshDir, baseName(mount))

        private fun publicFile(mount: String): File = File(sshDir, baseName(mount) + ".pub")

        fun exists(mount: String = ""): Boolean = identity(mount).isFile && publicFile(mount).isFile

        /** The line to paste into GitHub's deploy-key box. */
        fun publicKeyLine(mount: String = ""): String? = publicFile(mount).takeIf { it.isFile }?.readText()?.trim()

        suspend fun generate(
            mount: String = "",
            comment: String = "notes-droid",
        ): String =
            withContext(Dispatchers.IO) {
                val privateKey = identity(mount)
                val publicKey = publicFile(mount)
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

        /**
         * Writes the ssh config the session runs with, and returns it.
         *
         * Keepalives matter here rather than being a nicety: a mobile link goes
         * quiet while the server compresses objects, and without them an idle
         * connection is dropped part way through a transfer -- which surfaces
         * as the remote hanging up unexpectedly.
         */
        fun configFile(): File {
            val desired =
                buildString {
                    appendLine("Host *")
                    appendLine("    ServerAliveInterval 20")
                    appendLine("    ServerAliveCountMax 12")
                    appendLine("    TCPKeepAlive yes")
                    // There is no prompt on a phone and no known_hosts to seed.
                    appendLine("    StrictHostKeyChecking no")
                }
            val file = File(sshDir, "config")
            if (!file.isFile || file.readText() != desired) file.writeText(desired)
            return file
        }

        /**
         * The key's SHA-256 fingerprint, in the form GitHub shows.
         *
         * Without it there is no way to tell a key that was never registered
         * from one that was replaced by a reinstall, and both fail identically.
         */
        fun fingerprint(mount: String = ""): String = fingerprintOf(publicKeyLine(mount) ?: return "no key")

        private fun fingerprintOf(line: String): String =
            runCatching {
                KeyUtils.getFingerPrint(PublicKeyEntry.parsePublicKeyEntry(line).resolvePublicKey(null, null, null))
            }.getOrDefault("unreadable")

        /**
         * Every key on the device, whether or not a repository still claims it.
         *
         * Listed by file rather than derived from the configured repositories,
         * because the interesting case is the key left behind when a
         * repository is removed: nothing else would ever mention it, and it is
         * still a private key sitting in storage.
         */
        fun stored(): List<StoredKey> =
            sshDir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(PUBLIC_SUFFIX) }
                .map { file ->
                    val base = file.name.removeSuffix(PUBLIC_SUFFIX)
                    StoredKey(
                        fileName = base,
                        publicKey = file.readText().trim(),
                        fingerprint = fingerprintOf(file.readText().trim()),
                        createdAt = identityFile(base).lastModified().takeIf { it > 0 },
                    )
                }.sortedBy { it.fileName }

        fun deleteByFileName(fileName: String) {
            identityFile(fileName).delete()
            File(sshDir, fileName + PUBLIC_SUFFIX).delete()
        }

        private fun identityFile(base: String): File = File(sshDir, base)

        fun delete(mount: String = "") {
            identity(mount).delete()
            publicFile(mount).delete()
        }

        private companion object {
            /** Anything a file name should not carry, whatever a folder is called. */
            val UNSAFE = Regex("[^a-z0-9._-]")
            const val PUBLIC_SUFFIX = ".pub"
        }
    }

/** A key as it sits on disk, for the management screen. */
data class StoredKey(
    val fileName: String,
    val publicKey: String,
    val fingerprint: String,
    val createdAt: Long?,
) {
    /** `ssh-ed25519`, `ssh-rsa` -- whatever the line declares. */
    val algorithm: String get() = publicKey.substringBefore(' ')
}
