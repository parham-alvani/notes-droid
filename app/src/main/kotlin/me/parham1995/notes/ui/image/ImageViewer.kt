package me.parham1995.notes.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph
import kotlin.math.abs

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
) {
    val image by rememberVaultImageBytes(vaultId, path)
    ZoomableViewer(
        model = image.bytes,
        unavailable =
            stringResource(R.string.image_unavailable, path.substringAfterLast('/'))
                .takeIf { image.failed },
        caption = alt,
        contentDescription = alt ?: path.substringAfterLast('/'),
        onDismiss = onDismiss,
        onOpenExternally = onOpenExternally,
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
        val context = LocalContext.current

        var scale by remember(model) { mutableFloatStateOf(1f) }
        var offsetX by remember(model) { mutableFloatStateOf(0f) }
        var offsetY by remember(model) { mutableFloatStateOf(0f) }
        var frame by remember { mutableStateOf(IntSize.Zero) }

        fun clamp() {
            offsetX = offsetX.coerceIn(-panLimit(frame.width, scale), panLimit(frame.width, scale))
            offsetY = offsetY.coerceIn(-panLimit(frame.height, scale), panLimit(frame.height, scale))
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { frame = it },
            contentAlignment = Alignment.Center,
        ) {
            when (val loaded = model) {
                null ->
                    if (unavailable != null) {
                        Text(unavailable, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                else ->
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(loaded).build(),
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY,
                                ).pointerInput(model) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                        // Panning at rest would slide a
                                        // fitted image around inside a frame
                                        // it already fits.
                                        if (scale > 1f) {
                                            offsetX += pan.x
                                            offsetY += pan.y
                                        } else {
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                        clamp()
                                    }
                                }.pointerInput(model) {
                                    detectTapGestures(
                                        // Back to fitted if it is zoomed at
                                        // all, rather than only from exactly
                                        // 1x -- a double tap after pinching to
                                        // 1.2x should still be the way out.
                                        onDoubleTap = {
                                            if (abs(scale - 1f) > ZOOM_EPSILON) {
                                                scale = 1f
                                                offsetX = 0f
                                                offsetY = 0f
                                            } else {
                                                scale = DOUBLE_TAP_ZOOM
                                            }
                                        },
                                    )
                                },
                    )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
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
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
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
                            .padding(16.dp),
                )
            }
        }
    }
}

/**
 * How far the image may be pushed from centre, in pixels, before there is
 * nothing left of it to bring into view.
 *
 * At a given scale only `(scale - 1)` of a frame's worth hangs outside it, half
 * on each side. Panning past that just loses the image off the edge, which
 * takes a deliberate gesture to undo and reads as it having vanished. At or
 * below fitted there is no slack at all, so the answer is zero rather than
 * something negative -- which `coerceIn` would throw on.
 */
internal fun panLimit(
    frameSize: Int,
    scale: Float,
): Float = (frameSize * (scale - 1f) / 2f).coerceAtLeast(0f)

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOM_EPSILON = 0.01f
private const val SCRIM = 0.5f
