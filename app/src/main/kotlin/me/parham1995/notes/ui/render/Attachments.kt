package me.parham1995.notes.ui.render

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Hands a vault file to whatever application opens that kind of file.
 *
 * The reader deliberately does not try to display these itself. A PDF viewer,
 * a video player and an office suite are all already on the phone and all
 * better at it than anything that would fit in here, and the vault's own
 * attachments are few -- a couple of PDFs, a couple of videos, one recording.
 */
object Attachments {
    /**
     * Opens [file], or returns false when nothing on the device handles it.
     *
     * Returning false rather than throwing because "no app installed that
     * opens .drawio" is an ordinary thing for a vault to contain, not a fault.
     */
    fun open(
        context: Context,
        file: File,
    ): Boolean {
        val uri =
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }.getOrNull() ?: return false

        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeTypeOf(file.name))
                // The grant lasts as long as the receiving activity, and covers
                // this one URI. Nothing else in the app's storage is exposed.
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * The media type for a file name.
     *
     * Android's own [MimeTypeMap] is consulted first and is right about most
     * things, but it is the platform's table rather than a guarantee: it has no
     * entry for `epub` or `opus` on every release, and an attachment offered
     * under the wildcard type opens a chooser full of everything instead of
     * the one viewer that can read it. The table below covers what a vault
     * actually holds.
     */
    fun mimeTypeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return FALLBACK
        return KNOWN[extension]
            ?: MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(extension)
            ?: FALLBACK
    }

    /** What Lucide glyph stands for this kind of file, in a listing. */
    fun iconOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4", "mov", "mkv", "webm", "avi" -> "film"
            "mp3", "m4a", "ogg", "opus", "wav", "flac" -> "music"
            "zip", "tar", "gz", "7z" -> "file-archive"
            "xls", "xlsx", "ods", "csv" -> "file-spreadsheet"
            "ppt", "pptx", "odp" -> "presentation"
            "epub" -> "book-open-text"
            "pdf", "doc", "docx", "odt", "rtf", "txt" -> "file-text"
            else -> "paperclip"
        }

    private const val FALLBACK = "*/*"

    private val KNOWN =
        mapOf(
            "pdf" to "application/pdf",
            "epub" to "application/epub+zip",
            "txt" to "text/plain",
            "csv" to "text/csv",
            "rtf" to "application/rtf",
            "mp4" to "video/mp4",
            "mov" to "video/quicktime",
            "mkv" to "video/x-matroska",
            "webm" to "video/webm",
            "avi" to "video/x-msvideo",
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus",
            "wav" to "audio/wav",
            "flac" to "audio/flac",
            "zip" to "application/zip",
            "tar" to "application/x-tar",
            "gz" to "application/gzip",
            "7z" to "application/x-7z-compressed",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "odt" to "application/vnd.oasis.opendocument.text",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ods" to "application/vnd.oasis.opendocument.spreadsheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "odp" to "application/vnd.oasis.opendocument.presentation",
            // Obsidian's diagram plugin writes these; they are XML, and an
            // editor that understands them will claim the type.
            "drawio" to "application/xml",
        )
}
