package me.parham1995.notes.ui.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Reads a PDF in place.
 *
 * A dialog rather than a route, because the only way to name a destination
 * here would be the file's path -- and paths in this vault contain spaces,
 * `#`, and Persian, which is exactly the encoding minefield the rest of the
 * app avoids by navigating with integer ids.
 *
 * Pinch zooms and double tap toggles, the way the image viewer does, because a
 * page laid out to the width of a phone is a scan of A4 at about a fifth of
 * life size -- legible as a shape and not as words. Zoom is a layout change
 * rather than a scaled-up picture: the page is laid out wider than the window
 * and scrolled sideways, so the list still scrolls down at its own speed and
 * the page is re-rendered from the document at the size it is being shown at
 * instead of being magnified out of a bitmap made for the window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewer(
    file: File,
    title: String,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var failed by remember(file) { mutableStateOf(false) }
        // Opened and closed by the same thing, so the document cannot outlive
        // the viewer and cannot be closed while it is still being read.
        //
        // The effect that used to do the closing was keyed on `pages`, which
        // closed the document the instant it opened: `pages` going from null
        // to a document changes the key, the old effect is disposed, and its
        // cleanup reads `pages` -- by then the document that has just
        // arrived. The viewer drew nothing at all afterwards. The branch that
        // says "could not be read" had already been passed over, because the
        // page count was still right when it was tested and zero a moment
        // later when the list asked for it, leaving an empty list on the
        // window's own background. That is the "PDFs open black" this has
        // done from the beginning: nothing was ever rendered to be black.
        // `awaitDispose` closes the document this producer opened, never
        // whatever the state happens to hold by then.
        val pages by produceState<PdfPages?>(initialValue = null, file) {
            val opened = PdfPages.open(file)
            failed = opened == null
            value = opened
            awaitDispose { opened?.close() }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            LucideGlyph("x", size = 22.dp, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenExternally) {
                            LucideGlyph(
                                "external-link",
                                size = 20.dp,
                                contentDescription = stringResource(R.string.action_open_externally),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            val document = pages
            when {
                // Encrypted, truncated, or not really a PDF. Saying so and
                // offering the handoff is more use than a spinner that never
                // resolves -- another app may well open what this cannot.
                failed || (document != null && document.pageCount == 0) ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.pdf_unreadable),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onOpenExternally) { Text(stringResource(R.string.action_open_externally)) }
                    }

                document == null ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                else -> PdfPager(document, Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}

/** The pages, laid out at whatever magnification the reader has asked for. */
@Composable
private fun PdfPager(
    document: PdfPages,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val fitWidth = maxWidth
        var scale by remember(document) { mutableFloatStateOf(1f) }
        val sideways = rememberScrollState()
        val scope = rememberCoroutineScope()

        fun zoomTo(
            next: Float,
            around: Float,
        ) {
            val from = scale
            val to = next.coerceIn(1f, MAX_ZOOM)
            if (to == from) return
            scale = to
            val target = anchoredScroll(sideways.value, around, from, to)
            scope.launch { sideways.scrollTo(target) }
        }

        Box(
            Modifier
                .fillMaxSize()
                // Watched on the initial pass, so a second finger is taken as a
                // pinch before the list underneath has had the chance to read
                // the same movement as a drag. One finger is never consumed
                // here and reaches the list and the sideways scroll intact.
                .pointerInput(document) {
                    detectPinch { centroid, zoom -> zoomTo(scale * zoom, centroid.x) }
                },
        ) {
            Box(Modifier.fillMaxSize().horizontalScroll(sideways, enabled = scale > 1f)) {
                LazyColumn(
                    modifier = Modifier.width(fitWidth * scale).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items((0 until document.pageCount).toList(), key = { it }) { index ->
                        PdfPage(
                            document = document,
                            index = index,
                            scale = scale,
                            fitWidthPx = with(LocalDensity.current) { fitWidth.roundToPx() },
                            onDoubleTap = { at ->
                                // Back to fitted from anywhere above it, the
                                // way the image viewer behaves.
                                if (abs(scale - 1f) > ZOOM_EPSILON) zoomTo(1f, at.x) else zoomTo(DOUBLE_TAP_ZOOM, at.x)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    document: PdfPages,
    index: Int,
    scale: Float,
    fitWidthPx: Int,
    onDoubleTap: (Offset) -> Unit,
) {
    var ratio by remember(index) { mutableFloatStateOf(DEFAULT_RATIO) }
    LaunchedEffect(index) { ratio = document.aspectRatio(index) }
    val shape = ratio.takeIf { it > 0f } ?: DEFAULT_RATIO

    // Rendered at the size it is shown at rather than the page's natural one: a
    // page at 300dpi is 2,500 pixels wide and would be scaled straight back
    // down. Quantised to whole steps so that a pinch asks for two or three
    // renders rather than one per frame, and capped by what a single page is
    // worth holding -- past that the bitmap is stretched, which beats
    // allocating a hundred megabytes for one sheet of paper.
    val renderWidth =
        remember(fitWidthPx, scale, shape) {
            val step = ceil(scale).coerceIn(1f, MAX_RENDER_STEP)
            (fitWidthPx * step).coerceAtMost(budgetWidth(shape)).roundToInt().coerceAtLeast(1)
        }

    // Held across a re-render rather than produced fresh, so zooming in does
    // not blank the page it is zooming into while the bigger one is drawn.
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, renderWidth) {
        document.render(index, renderWidth)?.asImageBitmap()?.let { bitmap = it }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(shape)
            .background(Color.White)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = onDoubleTap) },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.pdf_page, index + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } ?: CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A pinch, and nothing else.
 *
 * Watched on the initial pass and consumed only once a second finger is down,
 * which is what lets a list and a sideways scroll live underneath it: they see
 * every one-finger gesture untouched, and never see the two-finger one.
 */
internal suspend fun PointerInputScope.detectPinch(onZoom: (centroid: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.count { it.pressed }
            if (down == 0) break
            if (down < 2) continue
            val zoom = event.calculateZoom()
            if (zoom != 1f) onZoom(event.calculateCentroid(useCurrent = true), zoom)
            event.changes.forEach { if (it.pressed) it.consume() }
        }
    }
}

/**
 * Where to scroll to so that the point under the fingers stays under them.
 *
 * The page is laid out wider as it is zoomed, so everything to the left of that
 * point grows by the same factor and has to be scrolled past. Without this the
 * page grows from its left edge and whatever was being read walks off the side
 * of the screen.
 */
internal fun anchoredScroll(
    scroll: Int,
    around: Float,
    from: Float,
    to: Float,
): Int = ((scroll + around) * (to / from) - around).roundToInt().coerceAtLeast(0)

/**
 * How wide a page may be rendered before it is not worth the memory.
 *
 * A bitmap is four bytes a pixel wherever it lives, so a budget in pixels is a
 * budget in megabytes: twelve million of them is 48MB, which one page of a
 * scanned document can have and several cannot. At A4 that lands a shade over
 * twice the width of a phone, so everything up to 2x is rendered rather than
 * magnified and beyond that the last render is stretched.
 */
internal fun budgetWidth(ratio: Float): Float =
    if (ratio <= 0f) MAX_RENDER_PIXELS else kotlin.math.sqrt(MAX_RENDER_PIXELS * ratio)

private const val MAX_RENDER_PIXELS = 12_000_000f
private const val MAX_RENDER_STEP = 3f
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOM_EPSILON = 0.01f

/** A4 in portrait, which is what an unrendered page is assumed to be. */
private const val DEFAULT_RATIO = 0.707f
