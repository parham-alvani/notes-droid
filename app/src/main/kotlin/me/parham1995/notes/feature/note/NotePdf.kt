package me.parham1995.notes.feature.note

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withTranslation
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.R
import me.parham1995.notes.markdown.CalloutKind
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.PrintedNote
import me.parham1995.notes.markdown.PrintedNote.Kind
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A note as an A4 PDF, for sending to someone who does not read markdown.
 *
 * `export.md` in notes-linux's `docs/specs/` is what Linux and the Mac make,
 * by running `daftar-cli export`; a phone cannot run it, so this draws the
 * same page with the platform's own PDF writer and text layout, as far as
 * that goes: A4, a bar along the top of every page, the title with the
 * folder and the day the note changed at the head of the first page and the
 * title on every other, the day of the export and `n / N` at the foot.
 * Vazirmatn for the text, as there, and code in a fixed width on grey.
 * Paragraphs are justified and English hyphenated; every paragraph runs in
 * the direction of its first strong letter, so Persian is justified from the
 * right. A callout has a bar in its kind's colour, a task its status, done
 * and cancelled greyed, and the Tasks plugin's signs are words. A picture of
 * the vault is drawn, never wider than the page. A page breaks between
 * lines, never through one.
 */
object NotePdf {
    /** What the head of the first page says about the note. */
    data class Head(
        val title: String,
        /** The folder the note is in; empty at the top of the vault. */
        val folder: String,
        /** The day the note last changed, when it is known. */
        val changed: LocalDate?,
        val exported: LocalDate = LocalDate.now(),
    )

    suspend fun share(
        context: Context,
        head: Head,
        blocks: List<MdBlock>,
        picture: suspend (String) -> File?,
    ) {
        val file = render(context, head, blocks, picture)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, head.title)
                .putExtra(Intent.EXTRA_SUBJECT, head.title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, head.title))
    }

    /** One thing on a page: a row of a text, or a picture. */
    private sealed interface Piece {
        val height: Float
        val gap: Float

        class Row(
            val layout: StaticLayout,
            val row: Int,
            val line: PrintedNote.Line,
            val left: Float,
            override val gap: Float,
        ) : Piece {
            override val height: Float get() = (layout.getLineBottom(row) - layout.getLineTop(row)).toFloat()
        }

        class Picture(
            val bitmap: Bitmap,
            val left: Float,
            val width: Float,
            override val height: Float,
            override val gap: Float,
        ) : Piece
    }

    suspend fun render(
        context: Context,
        head: Head,
        blocks: List<MdBlock>,
        picture: suspend (String) -> File? = { null },
    ): File {
        val fonts = Fonts(context)
        // Measured first, then laid out on pages, so every page can say how
        // many there are.
        val pieces = mutableListOf<Piece>()
        PrintedNote.of(blocks, head.title).forEach { line ->
            val barred = line.callout != null || line.kind == Kind.QUOTE
            val left = MARGIN + line.indent * INDENT + if (barred) BAR_ROOM else 0f
            val width = PAGE_WIDTH - MARGIN - left
            val path = line.image
            if (line.kind == Kind.IMAGE && path != null) {
                val bitmap = picture(path)?.let { load(context, it) }
                if (bitmap != null && bitmap.width > 0) {
                    val wide = minOf(width, line.width?.toFloat() ?: width, bitmap.width / PIXELS_PER_POINT)
                    val high = wide * bitmap.height / bitmap.width
                    val fit = minOf(1f, CONTENT_HEIGHT / high)
                    pieces += Piece.Picture(bitmap, left, wide * fit, high * fit, PARAGRAPH_GAP)
                } else {
                    // Not on the phone: its name, where it would have been.
                    val name = "↪ " + line.text.ifEmpty { path.substringAfterLast('/') }
                    pieces += rows(line.copy(text = name, kind = Kind.BODY), left, width, fonts)
                }
            } else {
                pieces += rows(line, left, width, fonts)
            }
        }

        // Which page each piece goes on, and how far down.
        val pages = mutableListOf(mutableListOf<Pair<Piece, Float>>())
        var y = FIRST_TOP
        pieces.forEach { piece ->
            val top = if (pages.last().isEmpty()) y else y + piece.gap
            if (top + piece.height > CONTENT_BOTTOM && pages.last().isNotEmpty()) {
                pages += mutableListOf(piece to LATER_TOP)
                y = LATER_TOP + piece.height
            } else {
                pages.last() += piece to top
                y = top + piece.height
            }
        }

        val document = PdfDocument()
        pages.forEachIndexed { index, placed ->
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), index + 1).create()
            val page = document.startPage(info)
            frame(page.canvas, context, head, index + 1, pages.size, fonts)
            placed.forEach { (piece, top) -> draw(page.canvas, piece, top) }
            document.finishPage(page)
        }
        val out = File(File(context.cacheDir, SHARED_DIR).apply { mkdirs() }, fileName(head.title))
        withContext(Dispatchers.IO) { out.outputStream().use(document::writeTo) }
        document.close()
        return out
    }

    /** [line]'s text laid out, one piece per row, so a page can break between any two. */
    private fun rows(
        line: PrintedNote.Line,
        left: Float,
        width: Float,
        fonts: Fonts,
    ): List<Piece> {
        val prose = line.kind == Kind.BODY || line.kind == Kind.QUOTE
        val layout =
            StaticLayout.Builder
                .obtain(line.text, 0, line.text.length, paintFor(line, fonts), width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setLineSpacing(0f, LINE_SPACING)
                .apply {
                    if (prose) {
                        setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
                        setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    }
                }.build()
        return (0 until layout.lineCount).map { row ->
            Piece.Row(layout, row, line, left, if (row == 0) spaceBefore(line) else 0f)
        }
    }

    private fun draw(
        canvas: Canvas,
        piece: Piece,
        top: Float,
    ) {
        when (piece) {
            is Piece.Picture -> {
                val box = RectF(piece.left, top, piece.left + piece.width, top + piece.height)
                canvas.drawBitmap(piece.bitmap, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            is Piece.Row -> {
                val line = piece.line
                val width = PAGE_WIDTH - MARGIN - piece.left
                val bottom = top + piece.height
                if (line.kind == Kind.CODE) {
                    canvas.drawRect(piece.left - CODE_PAD, top, piece.left + width, bottom, fill(CODE_BACKGROUND))
                }
                if (line.callout != null || line.kind == Kind.QUOTE) {
                    val bar = line.callout?.let(::calloutColor) ?: QUOTE_BAR
                    val x = piece.left - BAR_ROOM
                    canvas.drawRect(x, top, x + BAR_WIDTH, bottom, fill(bar))
                }
                val rowTop = piece.layout.getLineTop(piece.row).toFloat()
                canvas.withTranslation(piece.left, top - rowTop) {
                    clipRect(0f, rowTop, width, rowTop + piece.height)
                    piece.layout.draw(this)
                }
            }
        }
    }

    /** The bar along the top, the head, and the foot of page [number] of [count]. */
    private fun frame(
        canvas: Canvas,
        context: Context,
        head: Head,
        number: Int,
        count: Int,
        fonts: Fonts,
    ) {
        canvas.drawRect(0f, 0f, PAGE_WIDTH, TOP_BAR, fill(ACCENT))
        val days = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val small =
            TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                typeface = fonts.regular
                textSize = SMALL_SIZE
                color = GREY
            }
        if (number == 1) {
            val big =
                TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                    typeface = fonts.bold
                    textSize = TITLE_SIZE
                    color = Color.BLACK
                }
            val title = layout(head.title, big)
            canvas.withTranslation(MARGIN, MARGIN) { title.draw(this) }
            val under =
                listOfNotNull(
                    head.folder.takeIf { it.isNotEmpty() },
                    head.changed?.let { context.getString(R.string.pdf_changed, days.format(it)) },
                ).joinToString("  ·  ")
            val subtitle = layout(under, small)
            canvas.withTranslation(MARGIN, MARGIN + title.height + HEAD_GAP) { subtitle.draw(this) }
            val rule = MARGIN + title.height + HEAD_GAP + subtitle.height + HEAD_GAP
            canvas.drawRect(MARGIN, rule, PAGE_WIDTH - MARGIN, rule + RULE, fill(RULE_COLOR))
        } else {
            val title = layout(listOf(head.title, head.folder).filter { it.isNotEmpty() }.joinToString("  ·  "), small)
            canvas.withTranslation(MARGIN, MARGIN - title.height) { title.draw(this) }
            canvas.drawRect(MARGIN, MARGIN + HEAD_GAP, PAGE_WIDTH - MARGIN, MARGIN + HEAD_GAP + RULE, fill(RULE_COLOR))
        }
        val foot = PAGE_HEIGHT - MARGIN + FOOT_GAP
        val exported = layout(context.getString(R.string.pdf_exported, days.format(head.exported)), small)
        canvas.withTranslation(MARGIN, foot) { exported.draw(this) }
        val pageNumber = "$number / $count"
        canvas.drawText(pageNumber, PAGE_WIDTH - MARGIN - small.measureText(pageNumber), foot - small.ascent(), small)
    }

    private fun layout(
        text: String,
        paint: TextPaint,
    ): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, CONTENT_WIDTH.toInt())
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()

    /** A picture of the vault as a bitmap no larger than the page needs, through the app's own loader, SVG and all. */
    private suspend fun load(
        context: Context,
        file: File,
    ): Bitmap? {
        val request =
            ImageRequest
                .Builder(context)
                .data(file)
                .size(Size((CONTENT_WIDTH * PIXELS_PER_POINT).toInt(), (CONTENT_HEIGHT * PIXELS_PER_POINT).toInt()))
                .allowHardware(false)
                .build()
        return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    /** A name another app can show: the title, without what a file system refuses. */
    internal fun fileName(title: String): String =
        title
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ")
            .trim()
            .ifEmpty { "Note" }
            .take(MAX_NAME) + ".pdf"

    /** The colour of a callout's bar: green, amber, red, purple, grey, or the rest blue, as the Linux PDF has them. */
    internal fun calloutColor(kind: CalloutKind): Int =
        when (kind) {
            CalloutKind.TIP, CalloutKind.SUCCESS -> GREEN
            CalloutKind.WARNING, CalloutKind.QUESTION -> AMBER
            CalloutKind.DANGER, CalloutKind.BUG, CalloutKind.FAILURE -> RED
            CalloutKind.EXAMPLE -> PURPLE
            CalloutKind.QUOTE -> GREY
            else -> BLUE
        }

    /** Vazirmatn, the app's own Persian face, which has Latin letters too: the Linux PDF's text face. */
    private class Fonts(
        context: Context,
    ) {
        val regular: Typeface = ResourcesCompat.getFont(context, R.font.vazirmatn_regular) ?: Typeface.DEFAULT
        val bold: Typeface = ResourcesCompat.getFont(context, R.font.vazirmatn_bold) ?: Typeface.DEFAULT_BOLD
    }

    private fun paintFor(
        line: PrintedNote.Line,
        fonts: Fonts,
    ): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color =
                when {
                    line.faded -> GREY
                    // Vazirmatn has no italic: a quote is set in grey instead.
                    line.kind == Kind.QUOTE -> Color.DKGRAY
                    else -> Color.BLACK
                }
            when (line.kind) {
                Kind.HEADING -> {
                    typeface = fonts.bold
                    textSize = HEADING_SIZES.getOrElse(line.level) { BODY_SIZE }
                }
                Kind.CODE -> {
                    typeface = Typeface.MONOSPACE
                    textSize = CODE_SIZE
                }
                else -> {
                    typeface = fonts.regular
                    textSize = BODY_SIZE
                }
            }
        }

    private fun spaceBefore(line: PrintedNote.Line): Float =
        when (line.kind) {
            Kind.HEADING -> HEADING_GAP
            Kind.CODE -> 0f
            else -> PARAGRAPH_GAP
        }

    private fun fill(color: Int) = Paint().apply { this.color = color }

    // A4 in points, which is what PdfDocument measures in.
    private const val PAGE_WIDTH = 595f
    private const val PAGE_HEIGHT = 842f
    private const val MARGIN = 48f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val INDENT = 16f
    private const val BAR_ROOM = 10f
    private const val BAR_WIDTH = 3f
    private const val CODE_PAD = 4f
    private const val TOP_BAR = 6f
    private const val RULE = 0.75f
    private const val HEAD_GAP = 6f
    private const val FOOT_GAP = 12f
    private const val TITLE_SIZE = 20f
    private const val SMALL_SIZE = 8.5f
    private const val BODY_SIZE = 11f
    private const val CODE_SIZE = 9.5f
    private const val LINE_SPACING = 1.25f
    private const val PARAGRAPH_GAP = 6f
    private const val HEADING_GAP = 12f
    private const val MAX_NAME = 80

    /** Where the first page's text starts, under its head, and every other page's. */
    private const val FIRST_TOP = MARGIN + 64f
    private const val LATER_TOP = MARGIN + 18f
    private const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN
    private const val CONTENT_HEIGHT = CONTENT_BOTTOM - LATER_TOP

    /** A picture is drawn at twice the points it fills, for print. */
    private const val PIXELS_PER_POINT = 2f

    /** Level 1 to 6 are the note's headings. */
    private val HEADING_SIZES = listOf(20f, 17f, 15f, 13f, 12f, 11f, 11f)

    // The light theme's colours, as ARGB: plain numbers, so a JVM test can compare them.
    private val ACCENT = 0xFF3B6EA8.toInt()
    private val GREY = 0xFF808080.toInt()
    private val RULE_COLOR = 0xFFD0D0D0.toInt()
    private val CODE_BACKGROUND = 0xFFF2F2F2.toInt()
    private val QUOTE_BAR = 0xFFC0C0C0.toInt()
    private val BLUE = 0xFF3B6EA8.toInt()
    private val GREEN = 0xFF2E8B57.toInt()
    private val AMBER = 0xFFC88A12.toInt()
    private val RED = 0xFFC0392B.toInt()
    private val PURPLE = 0xFF7B4FB0.toInt()

    /** The only part of the cache the file provider exposes. */
    const val SHARED_DIR = "shared"
}
