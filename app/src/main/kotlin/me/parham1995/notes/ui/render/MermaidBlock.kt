package me.parham1995.notes.ui.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dagger.hilt.android.EntryPointAccessors
import me.parham1995.notes.R
import me.parham1995.notes.di.RendererEntryPoint
import me.parham1995.notes.ui.image.ZoomableViewer
import me.parham1995.notes.ui.mermaid.MermaidRenderer
import me.parham1995.notes.ui.mermaid.MermaidTheme
import java.io.File

/**
 * A mermaid diagram.
 *
 * Rendering happens once, offscreen, in the app's single shared WebView; what
 * lands here is the resulting SVG as an ordinary image, so the scroll list
 * never contains a WebView. Pinch-to-zoom is worth having because diagrams are
 * often wider than a phone.
 *
 * A diagram mermaid cannot parse falls back to its own source, which is more
 * use to a reader than an error.
 */
@Composable
fun MermaidBlockView(
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val renderer =
        remember {
            EntryPointAccessors
                .fromApplication(context.applicationContext, RendererEntryPoint::class.java)
                .mermaidRenderer()
        }

    val colors = MaterialTheme.colorScheme
    val theme =
        remember(colors) {
            MermaidTheme(
                background = colors.surface.hex(),
                primary = colors.surfaceVariant.hex(),
                primaryText = colors.onSurface.hex(),
                line = colors.outline.hex(),
                text = colors.onSurface.hex(),
                dark = colors.surface.luminance() < HALF,
            )
        }

    var svg by remember(code, theme) { mutableStateOf<File?>(null) }
    var error by remember(code, theme) { mutableStateOf<String?>(null) }

    LaunchedEffect(code, theme) {
        when (val result = renderer.render(code, theme)) {
            is MermaidRenderer.Result.Svg -> svg = result.file
            is MermaidRenderer.Result.Failed -> error = result.message
        }
    }

    // Diagrams are routinely wider than a phone. Pinching in place used to be
    // the answer and was half of one: it made the diagram bigger without
    // letting it be moved, so the corner that was wanted stayed off screen.
    // Tapping opens the same viewer an image opens, which pans and clamps.
    var opened by remember(code) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
            val file = svg
            when {
                file != null ->
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(file).build(),
                        contentDescription = stringResource(R.string.diagram),
                        modifier = Modifier.fillMaxWidth().clickable { opened = true },
                    )

                error != null ->
                    Column(Modifier.padding(12.dp)) {
                        // Saying so. A diagram that quietly became its own
                        // source looks like the vault is written that way,
                        // and for every diagram in it, it was.
                        Text(
                            text = stringResource(R.string.diagram_failed, error.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = code,
                            modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                else ->
                    Text(
                        stringResource(R.string.diagram_rendering),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }

    if (opened) {
        svg?.let { file ->
            ZoomableViewer(
                model = file,
                unavailable = null,
                caption = null,
                contentDescription = stringResource(R.string.diagram),
                onDismiss = { opened = false },
            )
        }
    }
}

private fun androidx.compose.ui.graphics.Color.hex(): String = "#%06X".format(toArgb() and WHITE_MASK)

private fun androidx.compose.ui.graphics.Color.luminance(): Float = (red + green + blue) / 3f

private const val WHITE_MASK = 0xFFFFFF
private const val HALF = 0.5f
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 5f
