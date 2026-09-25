package me.parham1995.notes.data

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.markdown.SearchText
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vaults as the system file picker sees them: one root each, read-only.
 *
 * This is what puts a note or an attachment in reach of an upload button in
 * any other app. The picker talks to [VaultDocumentsProvider], which is only a
 * binder-thread shell around this.
 *
 * A document id is `<vaultId>:<path>`, the path relative to that vault and
 * empty for its root. The id comes back from outside the app, so it is treated
 * as untrusted input the way a repository's paths are: the vault must still
 * exist, the path must stay inside that vault's own directory
 * ([VaultFileStore.fileFor] checks), and nothing hidden is reachable. Hidden
 * means any segment starting with a dot -- an SSH vault's `.git`, the
 * `.obsidian` folder -- which is repository machinery rather than anything a
 * person would pick.
 *
 * Nothing here can write. The roots advertise no create, rename or delete, and
 * [open] only ever hands out a read-only descriptor; the four edits the app
 * does make go through [VaultWriteRepository] and nowhere else.
 */
@Singleton
class VaultDocuments
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val files: VaultFileStore,
        private val vaults: VaultDao,
    ) {
        val authority: String get() = authorityOf(context)

        fun roots(projection: Array<out String>?): Cursor {
            val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
            val title = context.applicationInfo.loadLabel(context.packageManager).toString()
            synced().forEach { vault ->
                cursor.newRow().apply {
                    add(Root.COLUMN_ROOT_ID, vault.id.toString())
                    add(Root.COLUMN_DOCUMENT_ID, idOf(vault.id, ""))
                    add(Root.COLUMN_TITLE, title)
                    add(Root.COLUMN_SUMMARY, vault.label)
                    add(Root.COLUMN_ICON, context.applicationInfo.icon)
                    add(
                        Root.COLUMN_FLAGS,
                        Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH,
                    )
                }
            }
            cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildRootsUri(authority))
            return cursor
        }

        fun document(
            documentId: String,
            projection: Array<out String>?,
        ): Cursor {
            val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
            val (vaultId, path) = locate(documentId)
            row(cursor, vaultId, path, fileOf(vaultId, path))
            return cursor
        }

        fun children(
            parentId: String,
            projection: Array<out String>?,
        ): Cursor {
            val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
            val (vaultId, path) = locate(parentId)
            val directory = fileOf(vaultId, path)
            if (!directory.isDirectory) throw FileNotFoundException("not a folder: $parentId")
            directory
                .listFiles()
                .orEmpty()
                .filterNot { it.isMachinery() }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .forEach { row(cursor, vaultId, join(path, it.name), it) }
            cursor.setNotificationUri(
                context.contentResolver,
                DocumentsContract.buildChildDocumentsUri(authority, parentId),
            )
            return cursor
        }

        /**
         * Files in one vault whose name contains [query], folded the way the
         * quick switcher folds names -- so a Persian query finds a name typed
         * with Arabic letters.
         */
        fun search(
            rootId: String,
            query: String,
            projection: Array<out String>?,
        ): Cursor {
            val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
            val vaultId = rootId.toLongOrNull() ?: throw FileNotFoundException("no such root: $rootId")
            val wanted = SearchText.foldName(query.trim())
            if (wanted.isEmpty()) return cursor
            val root = fileOf(vaultId, "")
            root
                .walkTopDown()
                .onEnter { it == root || !it.isMachinery() }
                .filter { it.isFile && !it.isMachinery() && SearchText.foldName(it.name).contains(wanted) }
                .take(SEARCH_LIMIT)
                .forEach { row(cursor, vaultId, it.relativeTo(root).invariantSeparatorsPath, it) }
            return cursor
        }

        fun isChild(
            parentId: String,
            childId: String,
        ): Boolean {
            val (parentVault, parentPath) = parse(parentId) ?: return false
            val (childVault, childPath) = parse(childId) ?: return false
            return parentVault == childVault &&
                childPath != parentPath &&
                (parentPath.isEmpty() || childPath.startsWith("$parentPath/"))
        }

        /** The file behind [documentId], for reading. */
        fun open(documentId: String): File {
            val (vaultId, path) = locate(documentId)
            return fileOf(vaultId, path).takeIf { it.isFile } ?: throw FileNotFoundException(documentId)
        }

        /**
         * Tells an open picker to look again. Called after a sync and when a
         * vault comes or goes; everything this provider serves sits under the
         * authority, so one notification reaches every listing.
         */
        fun changed() {
            context.contentResolver.notifyChange("content://$authority".toUri(), null)
        }

        private fun synced(): List<VaultEntity> =
            runBlocking { vaults.all() }.filter { files.rootOf(it.id).isDirectory }

        private fun locate(documentId: String): Pair<Long, String> {
            val (vaultId, path) = parse(documentId) ?: throw FileNotFoundException("no such document: $documentId")
            if (path.split('/').any { it.startsWith(".") }) throw FileNotFoundException("no such document: $documentId")
            if (runBlocking { vaults.byId(vaultId) } == null) throw FileNotFoundException("no such vault: $vaultId")
            return vaultId to path
        }

        private fun fileOf(
            vaultId: Long,
            path: String,
        ): File {
            val file =
                try {
                    files.fileFor(vaultId, path)
                } catch (escape: IllegalArgumentException) {
                    throw FileNotFoundException(escape.message)
                }
            if (!file.exists()) throw FileNotFoundException("$vaultId:$path")
            return file
        }

        private fun row(
            cursor: MatrixCursor,
            vaultId: Long,
            path: String,
            file: File,
        ) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, idOf(vaultId, path))
                add(Document.COLUMN_DISPLAY_NAME, if (path.isEmpty()) vaultLabel(vaultId) else file.name)
                add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else mimeTypeOf(file.name))
                add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
                add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
                add(Document.COLUMN_FLAGS, 0)
            }
        }

        private fun vaultLabel(vaultId: Long): String = runBlocking { vaults.byId(vaultId) }?.label.orEmpty()

        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface Access {
            fun documents(): VaultDocuments
        }

        companion object {
            private const val SEARCH_LIMIT = 50

            fun authorityOf(context: Context): String = "${context.packageName}.documents"

            fun idOf(
                vaultId: Long,
                path: String,
            ): String = "$vaultId:$path"

            private fun parse(documentId: String): Pair<Long, String>? {
                val colon = documentId.indexOf(':')
                if (colon < 0) return null
                val vaultId = documentId.substring(0, colon).toLongOrNull() ?: return null
                return vaultId to documentId.substring(colon + 1).trim('/')
            }

            private fun join(
                parent: String,
                name: String,
            ) = if (parent.isEmpty()) name else "$parent/$name"

            private fun File.isMachinery() = name.startsWith(".")

            /**
             * Markdown first, because the platform's table does not know it on
             * every release, and a picker filtering on `text/` would skip the
             * one kind of file this app is about.
             */
            fun mimeTypeOf(name: String): String {
                val extension = name.substringAfterLast('.', "").lowercase()
                return when (extension) {
                    "md", "markdown" -> "text/markdown"
                    "canvas", "base" -> "application/json"
                    else ->
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                            ?: "application/octet-stream"
                }
            }

            private val ROOT_COLUMNS =
                arrayOf(
                    Root.COLUMN_ROOT_ID,
                    Root.COLUMN_DOCUMENT_ID,
                    Root.COLUMN_TITLE,
                    Root.COLUMN_SUMMARY,
                    Root.COLUMN_ICON,
                    Root.COLUMN_FLAGS,
                )

            private val DOCUMENT_COLUMNS =
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_SIZE,
                    Document.COLUMN_LAST_MODIFIED,
                    Document.COLUMN_FLAGS,
                )
        }
    }
