package me.parham1995.notes.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * A PDF, rendered a page at a time.
 *
 * [PdfRenderer] allows exactly one page open at once and is not safe to use
 * from more than one thread, which a lazy list will absolutely try to do --
 * several pages come into view together and each wants rendering. The mutex is
 * what makes that legal rather than a crash.
 *
 * Pages are rendered on demand and not cached here: a page of a dense document
 * at phone width is a couple of megabytes, and holding all of them is how a
 * viewer runs a 200MB heap into the ground. Compose keeps the visible ones
 * alive and drops the rest.
 */
class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    private val mutex = Mutex()

    val pageCount: Int get() = renderer.pageCount

    /** The aspect ratio of a page, so the list can size it before rendering. */
    suspend fun aspectRatio(index: Int): Float =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                renderer.openPage(index).use { page ->
                    page.width.toFloat() / page.height.toFloat()
                }
            }
        }

    suspend fun render(
        index: Int,
        widthPx: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val height = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                        val bitmap = createBitmap(widthPx, height)
                        // A PDF page assumes paper. Without this, anything the
                        // page does not draw stays transparent and composites
                        // to black against a dark theme -- which looks like a
                        // rendering failure rather than a margin.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }.getOrNull()
            }
        }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /** Null when the file is not a PDF, or is one this device cannot open. */
        suspend fun open(file: File): PdfPages? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    PdfPages(descriptor, PdfRenderer(descriptor))
                }.getOrNull()
            }
    }
}
