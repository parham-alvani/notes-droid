package me.parham1995.notes.ui.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.parham1995.notes.ui.icon.LucideGlyph
import java.io.File

/**
 * Reads a PDF in place.
 *
 * A dialog rather than a route, because the only way to name a destination
 * here would be the file's path -- and paths in this vault contain spaces,
 * `#`, and Persian, which is exactly the encoding minefield the rest of the
 * app avoids by navigating with integer ids.
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
        val pages by produceState<PdfPages?>(initialValue = null, file) {
            val opened = PdfPages.open(file)
            failed = opened == null
            value = opened
        }
        // Held open for as long as the viewer is, and closed exactly once:
        // a leaked descriptor survives the dialog and the file stays locked.
        DisposableEffect(pages) {
            onDispose { pages?.close() }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            LucideGlyph("x", size = 22.dp, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenExternally) {
                            LucideGlyph("external-link", size = 20.dp, contentDescription = "Open in another app")
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
                            "This PDF could not be read here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onOpenExternally) { Text("Open in another app") }
                    }

                document == null ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding =
                            androidx.compose.foundation.layout
                                .PaddingValues(8.dp),
                    ) {
                        items((0 until document.pageCount).toList(), key = { it }) { index ->
                            PdfPage(document, index)
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
) {
    // Rendered at the window's own width rather than the page's natural size:
    // a page at 300dpi is 2,500 pixels wide and would be scaled straight back
    // down. The window, not `Configuration.screenWidthDp`, because that one
    // rounds to whole dp and treats insets differently per target SDK.
    val density = LocalDensity.current
    val containerWidth = LocalWindowInfo.current.containerSize.width
    val widthPx =
        remember(containerWidth, density) {
            val margin = with(density) { PAGE_MARGIN.roundToPx() }
            (containerWidth - margin).coerceAtLeast(1)
        }

    var ratio by remember(index) { mutableFloatStateOf(DEFAULT_RATIO) }
    LaunchedEffect(index) { ratio = document.aspectRatio(index) }

    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, index, widthPx) {
        value = document.render(index, widthPx)?.asImageBitmap()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.takeIf { it > 0f } ?: DEFAULT_RATIO)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } ?: CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

private val PAGE_MARGIN = 16.dp

/** A4 in portrait, which is what an unrendered page is assumed to be. */
private const val DEFAULT_RATIO = 0.707f
