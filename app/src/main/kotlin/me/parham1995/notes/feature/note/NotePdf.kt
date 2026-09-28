package me.parham1995.notes.feature.note

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.withTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.PrintedNote
import me.parham1995.notes.markdown.PrintedNote.Kind
import java.io.File

/**
 * A note as an A4 PDF, for sending to someone who does not read markdown.
 *
 * Drawn with the platform's own PDF writer and text layout: headings larger,
 * code in a fixed width, quotes set in and grey, and every paragraph in the
 * direction its first letter reads -- Persian right to left beside English.
 * A page breaks between lines, never through one.
 */
object NotePdf {
    suspend fun share(
        context: Context,
        title: String,
        blocks: List<MdBlock>,
    ) {
        val file = withContext(Dispatchers.Default) { render(context, title, blocks) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, title)
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title))
    }

    fun render(
        context: Context,
        title: String,
        blocks: List<MdBlock>,
    ): File {
        val lines = listOf(PrintedNote.Line(title, Kind.HEADING, level = 0)) + PrintedNote.of(blocks)
        val document = PdfDocument()
        var number = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage(): Canvas {
            page?.let(document::finishPage)
            number += 1
            val started = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create())
            page = started
            y = MARGIN
            return started.canvas
        }

        var canvas = newPage()
        lines.forEach { line ->
            val left = MARGIN + line.indent * INDENT
            val width = (PAGE_WIDTH - MARGIN - left).toInt()
            val layout =
                StaticLayout.Builder
                    .obtain(line.text, 0, line.text.length, paintFor(line), width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setLineSpacing(0f, LINE_SPACING)
                    .build()
            y += spaceBefore(line)
            for (row in 0 until layout.lineCount) {
                val top = layout.getLineTop(row).toFloat()
                val height = layout.getLineBottom(row) - top
                if (y + height > PAGE_HEIGHT - MARGIN) canvas = newPage()
                canvas.withTranslation(left, y - top) {
                    clipRect(0f, top, width.toFloat(), top + height)
                    layout.draw(this)
                }
                y += height
            }
        }
        page?.let(document::finishPage)

        val out = File(File(context.cacheDir, SHARED_DIR).apply { mkdirs() }, fileName(title))
        out.outputStream().use(document::writeTo)
        document.close()
        return out
    }

    /** A name another app can show: the title, without what a file system refuses. */
    internal fun fileName(title: String): String =
        title
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ")
            .trim()
            .ifEmpty { "Note" }
            .take(MAX_NAME) + ".pdf"

    private fun paintFor(line: PrintedNote.Line): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = if (line.kind == Kind.QUOTE) Color.DKGRAY else Color.BLACK
            when (line.kind) {
                Kind.HEADING -> {
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = HEADING_SIZES.getOrElse(line.level) { BODY_SIZE }
                }
                Kind.CODE -> {
                    typeface = Typeface.MONOSPACE
                    textSize = CODE_SIZE
                }
                Kind.QUOTE -> {
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                    textSize = BODY_SIZE
                }
                Kind.BODY -> textSize = BODY_SIZE
            }
        }

    private fun spaceBefore(line: PrintedNote.Line): Float =
        when (line.kind) {
            Kind.HEADING -> HEADING_GAP
            Kind.CODE -> 0f
            else -> PARAGRAPH_GAP
        }

    // A4 in points, which is what PdfDocument measures in.
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val INDENT = 16f
    private const val BODY_SIZE = 11f
    private const val CODE_SIZE = 9.5f
    private const val LINE_SPACING = 1.2f
    private const val PARAGRAPH_GAP = 6f
    private const val HEADING_GAP = 12f
    private const val MAX_NAME = 80

    /** Level 0 is the note's own title; 1 to 6 are its headings. */
    private val HEADING_SIZES = listOf(20f, 17f, 15f, 13f, 12f, 11f, 11f)

    /** The only part of the cache the file provider exposes. */
    const val SHARED_DIR = "shared"
}
