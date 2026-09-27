package me.parham1995.notes.data

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.markdown.SearchText
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
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
        private val notes: NoteDao,
        private val lock: AppLock,
        private val settings: SettingsStore,
    ) {
        val authority: String get() = authorityOf(context)

        fun roots(projection: Array<out String>?): Cursor {
            val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
            cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildRootsUri(authority))
            // Locked: no roots at all, rather than roots that fail when opened.
            if (locked()) return cursor
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
                        Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH or
                            Root.FLAG_SUPPORTS_RECENTS,
                    )
                }
            }
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
            if (locked()) throw FileNotFoundException("locked")
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

        /**
         * The notes last read in the app, newest first -- what the picker's
         * Recent list shows for this vault. The note that was open a minute
         * ago is the likeliest thing to be sent somewhere.
         */
        fun recents(
            rootId: String,
            projection: Array<out String>?,
        ): Cursor {
            val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
            val vaultId = rootId.toLongOrNull() ?: throw FileNotFoundException("no such root: $rootId")
            if (locked()) throw FileNotFoundException("locked")
            if (runBlocking { vaults.byId(vaultId) } == null) throw FileNotFoundException("no such vault: $vaultId")
            runBlocking { notes.recentlyOpened(vaultId, RECENT_LIMIT).first() }
                .filterNot { note -> note.path.split('/').any { it.startsWith(".") } }
                .forEach { note ->
                    val file = runCatching { fileOf(vaultId, note.path) }.getOrNull() ?: return@forEach
                    if (file.isFile && !file.isMachinery()) row(cursor, vaultId, note.path, file)
                }
            return cursor
        }

        /**
         * A small JPEG of an image in a vault, for the picker's grid.
         *
         * Decoded at a fraction of its size -- a photo from a phone is twelve
         * megapixels, and the picker asks for a thumbnail per cell -- and kept
         * in the cache directory under a name that changes with the file.
         */
        fun thumbnail(
            documentId: String,
            width: Int,
            height: Int,
        ): File {
            val source = open(documentId)
            if (!mimeTypeOf(source.name).startsWith("image/")) throw FileNotFoundException("not an image: $documentId")
            val out =
                File(
                    File(context.cacheDir, "thumbnails").apply { mkdirs() },
                    "${(documentId + source.lastModified() + source.length()).hashCode()}-${width}x$height.jpg",
                )
            if (out.isFile) return out
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
            val bitmap =
                BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw FileNotFoundException("cannot decode: $documentId")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, it) }
            bitmap.recycle()
            return out
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

        /**
         * Whether the app lock keeps the vaults from other apps just now.
         *
         * Checked on every call, not only for the roots: an app that was handed
         * a document once can keep the link and open it again later, and the
         * lock has to hold against that too.
         */
        private fun locked(): Boolean {
            val privacy = runBlocking { settings.current() }.privacy
            return privacy.appLock && privacy.lockPicker && !lock.isOpen()
        }

        private fun locate(documentId: String): Pair<Long, String> {
            if (locked()) throw FileNotFoundException("locked")
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
                add(
                    Document.COLUMN_FLAGS,
                    if (!file.isDirectory &&
                        mimeTypeOf(file.name).startsWith("image/")
                    ) {
                        Document.FLAG_SUPPORTS_THUMBNAIL
                    } else {
                        0
                    },
                )
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
            private const val RECENT_LIMIT = 20
            private const val THUMBNAIL_QUALITY = 85

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

            /**
             * What the picker never offers: repository machinery, and links.
             * A symlink can point anywhere -- opening one is refused already,
             * because [VaultFileStore.fileFor] resolves it -- but listing it
             * showed a name and a size from outside the vault, and the search
             * walked through it into whatever it named.
             */
            private fun File.isMachinery() = name.startsWith(".") || Files.isSymbolicLink(toPath())

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
