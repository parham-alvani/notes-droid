package me.parham1995.notes.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.data.database.VaultEntity
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
 * Keys are named after the vault's id, which is the one thing about a vault
 * that never changes and is never shared. They used to be named after the
 * vault's *name*, lowercased with anything outside `[a-z0-9._-]` replaced --
 * so every Persian name collapsed to `id_____`, `Work` and `work` were one
 * file, and renaming a vault orphaned its key. [adoptLegacyNames] moves keys
 * from those names once, so a deploy key already registered keeps working.
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

        private val _revision = MutableStateFlow(0)

        /**
         * Bumped whenever a key is created or removed.
         *
         * The keys are files and a filesystem has nothing to subscribe to, so
         * anything showing them has to be told. Without this a screen listing
         * keys is only ever as current as the last time it was built -- which
         * is how the settings screen came to show no key for a repository that
         * had just been given one.
         */
        val revision: StateFlow<Int> = _revision.asStateFlow()

        val directory: File get() = sshDir

        private fun baseName(vaultId: Long): String = "id_vault_$vaultId"

        fun identity(vaultId: Long): File = File(sshDir, baseName(vaultId))

        private fun publicFile(vaultId: Long): File = File(sshDir, baseName(vaultId) + PUBLIC_SUFFIX)

        fun exists(vaultId: Long): Boolean = identity(vaultId).isFile && publicFile(vaultId).isFile

        /** The line to paste into GitHub's deploy-key box. */
        fun publicKeyLine(vaultId: Long): String? = publicFile(vaultId).takeIf { it.isFile }?.readText()?.trim()

        /**
         * Moves each SSH vault's key from the file its name used to give it to
         * the one its id gives it. Idempotent, and cheap once done: a key that
         * is already where it belongs is left alone.
         *
         * Two vaults whose names collapsed to the same file -- `Work` and
         * `work`, or any two Persian names -- were sharing one key, and it can
         * only be registered on one of the two repositories. The vault that
         * was added first keeps it; the other finds no key and is asked to
         * generate its own, which is what it needed all along.
         *
         * Must be handed *every* vault, never one: which vault was first is
         * the whole decision, and a partial list would hand a shared file to
         * whichever vault happened to be asked about first.
         */
        fun adoptLegacyNames(vaults: List<VaultEntity>) {
            val claimed = mutableSetOf<String>()
            var moved = false
            vaults
                .filter { SyncTransport.parse(it.transport) == SyncTransport.SSH }
                .sortedBy { it.id }
                .forEach { vault ->
                    val legacy = legacyName(vault.name)
                    // Claimed whether or not this vault takes it, so a later
                    // vault can never inherit a file an earlier one owns.
                    if (!claimed.add(legacy) || exists(vault.id)) return@forEach
                    val oldPrivate = File(sshDir, legacy)
                    val oldPublic = File(sshDir, legacy + PUBLIC_SUFFIX)
                    if (!oldPrivate.isFile || !oldPublic.isFile) return@forEach
                    if (oldPrivate.renameTo(identity(vault.id))) {
                        if (oldPublic.renameTo(publicFile(vault.id))) {
                            moved = true
                        } else {
                            // Half a key is no key; put the private half back
                            // where it was and try again next time.
                            identity(vault.id).renameTo(oldPrivate)
                        }
                    }
                }
            if (moved) _revision.value++
        }

        suspend fun generate(
            vaultId: Long,
            comment: String = "notes-droid",
        ): String =
            withContext(Dispatchers.IO) {
                val privateKey = identity(vaultId)
                val publicKey = publicFile(vaultId)
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
                _revision.value++
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
                    // Host keys are checked against GitHub's published ones
                    // (PinnedHostKeys); an unknown key is refused, never
                    // accepted, because there is nobody on a phone to ask.
                    appendLine("    StrictHostKeyChecking yes")
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
        fun fingerprint(vaultId: Long): String = fingerprintOf(publicKeyLine(vaultId) ?: return "no key")

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
            _revision.value++
        }

        private fun identityFile(base: String): File = File(sshDir, base)

        fun delete(vaultId: Long) {
            identity(vaultId).delete()
            publicFile(vaultId).delete()
            _revision.value++
        }

        private companion object {
            /** Anything a file name should not carry, whatever a folder is called. */
            val UNSAFE = Regex("[^a-z0-9._-]")
            const val PUBLIC_SUFFIX = ".pub"

            /** The file a vault's name used to give its key. */
            fun legacyName(name: String): String =
                if (name.isEmpty()) "id_notes" else "id_" + name.lowercase().replace(UNSAFE, "_")
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
