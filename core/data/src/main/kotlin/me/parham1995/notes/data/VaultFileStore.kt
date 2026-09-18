package me.parham1995.notes.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vaults' working trees on disk.
 *
 * Two things matter here. Writes are atomic -- temp file then rename -- so an
 * interrupted sync can never leave a half-written note that later looks intact.
 * And every path is normalised to NFC before it touches the filesystem: GitHub
 * returns paths exactly as committed, while a filesystem may hand back a
 * different Unicode composition, and a vault with accented or non-Latin
 * filenames would otherwise grow duplicates that never match the manifest.
 */
@Singleton
class VaultFileStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        val root: File = File(context.filesDir, "vault")

        /** Where one vault's working tree lives. */
        fun rootOf(vaultId: Long): File = File(root, vaultId.toString())

        /** Absolute file for a [path] relative to [vaultId]. */
        fun fileFor(
            vaultId: Long,
            path: String,
        ): File {
            val normalized = normalize(path)
            val vaultRoot = rootOf(vaultId)
            val file = File(vaultRoot, normalized)
            // A repository is untrusted input. A path escaping the vault root
            // would let a crafted entry write anywhere the app can -- including
            // into another vault, which is why this is checked against the
            // vault's own directory rather than the shared parent.
            val canonicalRoot = vaultRoot.canonicalPath
            val canonicalFile = file.canonicalPath
            require(canonicalFile == canonicalRoot || canonicalFile.startsWith(canonicalRoot + File.separator)) {
                "path escapes the vault root: $path"
            }
            return file
        }

        suspend fun write(
            vaultId: Long,
            path: String,
            bytes: ByteArray,
        ) = withContext(Dispatchers.IO) {
            val target = fileFor(vaultId, path)
            target.parentFile?.mkdirs()
            val temp = File.createTempFile("write", null, target.parentFile ?: root)
            try {
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) {
                    // renameTo will not replace on every filesystem.
                    if (!target.delete() && target.exists()) throw IOException("cannot replace $path")
                    if (!temp.renameTo(target)) throw IOException("cannot write $path")
                }
            } finally {
                temp.delete()
            }
        }

        suspend fun read(
            vaultId: Long,
            path: String,
        ): ByteArray? =
            withContext(Dispatchers.IO) {
                fileFor(vaultId, path).takeIf { it.isFile }?.readBytes()
            }

        suspend fun readText(
            vaultId: Long,
            path: String,
        ): String? = read(vaultId, path)?.decodeToString()

        suspend fun move(
            vaultId: Long,
            from: String,
            to: String,
        ) = withContext(Dispatchers.IO) {
            val source = fileFor(vaultId, from)
            val target = fileFor(vaultId, to)
            if (!source.exists()) return@withContext
            target.parentFile?.mkdirs()
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            pruneEmptyParents(source.parentFile)
        }

        suspend fun delete(
            vaultId: Long,
            path: String,
        ) = withContext(Dispatchers.IO) {
            val file = fileFor(vaultId, path)
            file.delete()
            pruneEmptyParents(file.parentFile)
        }

        /**
         * Everything one vault holds, for a repository being detached --
         * including the `.git` directory a clone leaves behind.
         */
        suspend fun deleteVault(vaultId: Long) =
            withContext(Dispatchers.IO) {
                rootOf(vaultId).deleteRecursively()
                Unit
            }

        suspend fun exists(
            vaultId: Long,
            path: String,
        ): Boolean = withContext(Dispatchers.IO) { fileFor(vaultId, path).isFile }

        /** Bytes currently occupied by the working tree. */
        suspend fun sizeOnDisk(): Long =
            withContext(Dispatchers.IO) {
                root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }

        suspend fun clear() =
            withContext(Dispatchers.IO) {
                root.deleteRecursively()
                Unit
            }

        /** Removes directories left empty by a move or delete, up to the root. */
        private fun pruneEmptyParents(from: File?) {
            var directory = from
            while (directory != null &&
                directory != root &&
                directory.startsWith(root) &&
                directory.isDirectory &&
                directory.list()?.isEmpty() == true
            ) {
                if (!directory.delete()) return
                directory = directory.parentFile
            }
        }

        private companion object {
            fun normalize(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC)
        }
    }
