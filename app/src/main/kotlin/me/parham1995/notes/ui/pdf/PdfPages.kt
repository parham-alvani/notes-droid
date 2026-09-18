package me.parham1995.notes.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 *
 * Closing is the part that has to be right. A lazy list has several page
 * renders in flight at any moment, and closing the dialog used to close the
 * renderer out from under them -- the next one to resume called `openPage` on
 * a closed renderer and threw, out of a coroutine, taking the app with it.
 * `closed` stops new work at once and the release itself waits for the mutex,
 * so nothing is ever torn down mid-render and nothing blocks the frame that
 * dismissed the dialog.
 */
class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    private val mutex = Mutex()

    @Volatile
    private var closed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val pageCount: Int get() = if (closed) 0 else runCatching { renderer.pageCount }.getOrDefault(0)

    /**
     * The aspect ratio of a page, so the list can size it before rendering.
     *
     * Returns zero rather than throwing when the document has gone: the caller
     * is a composable that has already been left, and a thrown exception there
     * is a crash rather than a missing page.
     */
    suspend fun aspectRatio(index: Int): Float =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock 0f
                runCatching {
                    renderer.openPage(index).use { page ->
                        page.width.toFloat() / page.height.toFloat()
                    }
                }.getOrDefault(0f)
            }
        }

    suspend fun render(
        index: Int,
        widthPx: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock null
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
        // Marked first and released after: marking stops anything new from
        // starting, and taking the mutex lets whatever is already rendering
        // finish against a renderer that is still open.
        closed = true
        scope.launch {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
            scope.cancel()
        }
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
