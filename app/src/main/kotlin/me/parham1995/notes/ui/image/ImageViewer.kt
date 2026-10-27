package me.parham1995.notes.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.request.ImageRequest
import me.parham1995.notes.R
import me.parham1995.notes.ui.Loading
import me.parham1995.notes.ui.icon.LucideGlyph
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * An embedded image, full screen and zoomable.
 *
 * Opened by a tap rather than a press and hold. The note body is a
 * `SelectionContainer`, where holding is how text is selected, and a gesture
 * that means two things depending on what is underneath it is a gesture that
 * feels broken in both.
 *
 * Holding to magnify would also solve the smaller half of the problem. What is
 * actually wanted from a screenshot of a dashboard or a page of a scanned
 * document is to get close to one corner of it and move around, which needs a
 * surface of its own to pan on.
 */
@Composable
fun ImageViewer(
    vaultId: Long,
    path: String,
    alt: String?,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
    /** On the picture itself, not the layer, for sharing it with where it came from. */
    modifier: Modifier = Modifier,
) {
    val image by rememberVaultImage(vaultId, path)
    // In the window, not a dialog: the caller animates it in and out, and a
    // picture shared with the page has to be in the same window as the page.
    ZoomableContent(
        model = image.file,
        unavailable =
            stringResource(R.string.image_unavailable, path.substringAfterLast('/'))
                .takeIf { image.failed },
        caption = alt,
        contentDescription = alt ?: path.substringAfterLast('/'),
        onDismiss = onDismiss,
        onOpenExternally = onOpenExternally,
        modifier = modifier,
    )
}

/**
 * Anything flat, full screen and zoomable: a vault image, a rendered diagram.
 *
 * [model] is handed straight to Coil, so it can be bytes or a file. Null means
 * it is still coming, unless [unavailable] says it is not coming at all.
 */
@Composable
fun ZoomableViewer(
    model: Any?,
    unavailable: String?,
    caption: String?,
    contentDescription: String?,
    onDismiss: () -> Unit,
    onOpenExternally: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ZoomableContent(model, unavailable, caption, contentDescription, onDismiss, onOpenExternally)
    }
}

/** The viewer itself, for a dialog or for a layer over the page. */
@Composable
internal fun ZoomableContent(
    model: Any?,
    unavailable: String?,
    caption: String?,
    contentDescription: String?,
    onDismiss: () -> Unit,
    onOpenExternally: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Over a page, a tap on the black must not reach the page.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        when (val loaded = model) {
            null ->
                if (unavailable != null) {
                    Text(unavailable, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Loading()
                }

            else -> ZoomablePicture(loaded, contentDescription, modifier)
        }

        // Over a page the layer runs under the status bar, so the
        // controls step inside it.
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp),
        ) {
            LucideGlyph(
                "x",
                size = 22.dp,
                contentDescription = stringResource(R.string.action_close),
                tint = Color.White,
            )
        }

        // The same way out the PDF viewer offers. An image in a note is
        // often the thing being taken somewhere else -- sent to someone,
        // marked up, saved -- and a viewer with no exit makes that a trip
        // through the file manager.
        onOpenExternally?.let { open ->
            IconButton(
                onClick = open,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
            ) {
                LucideGlyph(
                    "external-link",
                    size = 20.dp,
                    contentDescription = stringResource(R.string.action_open_externally),
                    tint = Color.White,
                )
            }
        }

        caption?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = SCRIM))
                        .safeDrawingPadding()
                        .padding(16.dp),
            )
        }
    }
}

/**
 * The picture, zoomed by Telephoto.
 *
 * The viewer used to scale a plain `AsyncImage` in a graphics layer, and it
 * showed: a pinch zoomed about the middle of the screen rather than between
 * the fingers, a double tap zoomed the middle rather than what was tapped, a
 * pan stopped dead when the finger lifted, and the picture was decoded at the
 * size of the screen, so a screenshot zoomed six times was a blur of it.
 * Telephoto does all four: it zooms about the fingers and the tap, flings,
 * and reads a large bitmap again in tiles at the resolution the zoom needs.
 *
 * Tiles are for still bitmaps only. A GIF or animated WebP, an AVIF and an SVG
 * are drawn whole through Coil's painter -- Telephoto checks the file's header
 * and steps aside -- so a GIF keeps playing under the zoom. An SVG is asked for
 * as paths ([asVector]) rather than a bitmap, which the zoom then draws at its
 * own scale, so a diagram is as sharp at 6x as at 1x.
 */
@Composable
internal fun ZoomablePicture(
    model: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    /** Where the zoom is, held outside only for a test to read. */
    zoom: ZoomableImageState =
        rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))),
) {
    val context = LocalContext.current
    val dark = pageIsDark()
    val request =
        remember(model, dark) {
            ImageRequest
                .Builder(context)
                .data(model)
                // The page's theme, not the black behind the viewer: the
                // picture grows out of the page and must not change colour
                // on the way.
                .onPage(dark)
                .asVector()
                .build()
        }
    ZoomableAsyncImage(
        model = request,
        contentDescription = contentDescription,
        state = zoom,
        contentScale = ContentScale.Fit,
        onDoubleClick = ZoomInOrOut,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * A double tap zooms in on the point tapped, or back to fitted if the picture
 * is zoomed at all -- not only from exactly the zoom a double tap gives. Back
 * to fitted after pinching to 1.2x should still be one double tap, where
 * Telephoto's own cycle would zoom in further.
 */
private val ZoomInOrOut =
    object : DoubleClickToZoomListener {
        override suspend fun onDoubleClick(
            state: ZoomableState,
            centroid: Offset,
        ) {
            val zoomed = state.zoomFraction ?: return
            if (zoomed > ZOOMED_FRACTION) {
                state.resetZoom()
            } else {
                state.zoomBy(zoomFactor = DOUBLE_TAP_ZOOM, centroid = centroid)
            }
        }
    }

/**
 * Six times the picture's own pixels, in Telephoto's terms, or six times the
 * screen for one that is smaller than the screen. A large screenshot fitted
 * at a quarter of its size can be taken past every one of its pixels; a small
 * one zooms as far as it did when the limit was six times fitted.
 */
private const val MAX_ZOOM = 6f

/** Times what is on the screen, so the same step for a photo and an icon. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * How far from fitted, as a share of the way to [MAX_ZOOM], counts as zoomed:
 * anything but rounding. The way is long for a large picture -- fitted at a
 * quarter of its size, six times its pixels is 24 times the screen -- so a
 * share of 1% let a pinch to 1.2x count as fitted, and a double tap zoomed in
 * further instead of out.
 */
private const val ZOOMED_FRACTION = 0.001f
private const val SCRIM = 0.5f
