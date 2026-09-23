package me.parham1995.notes.ui.render

import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.inScript
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Renders LaTeX with JLaTeXMath, which is pure Java and needs no WebView.
 *
 * Rendering produces a bitmap, so it is done off the main thread and cached by
 * the formula together with the size and colour it was drawn at -- the same
 * formula in a different theme is a different bitmap.
 *
 * The library is old and does not cover every AMS environment. A formula it
 * cannot parse falls back to its own source in a monospace box, which is
 * strictly more useful to a reader than an error or a blank space.
 */
object MathRender {
    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    suspend fun render(
        latex: String,
        textSizePx: Float,
        colorArgb: Int,
    ): ImageBitmap? {
        val key = "$latex|$textSizePx|$colorArgb"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.Default) {
            runCatching {
                val drawable =
                    JLatexMathDrawable
                        .builder(latex)
                        .textSize(textSizePx)
                        .color(colorArgb)
                        .background(0)
                        .build()

                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                val bitmap = createBitmap(width, height)
                android.graphics.Canvas(bitmap).let { canvas ->
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(canvas)
                }
                bitmap.asImageBitmap().also { cache.put(key, it) }
            }.getOrNull()
        }
    }

    private const val CACHE_ENTRIES = 128
}

/** A display formula on its own line, scrollable when it overflows. */
@Composable
fun MathBlockView(
    latex: String,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    // The reader's size, like the prose around it: a formula that stayed put
    // while the sentence introducing it doubled read as a footnote.
    val sizePx =
        with(density) {
            MaterialTheme.typography.bodyLarge.fontSize
                .toPx()
        } * DISPLAY_SCALE * LocalReading.current.textScale
    var bitmap by remember(latex, color, sizePx) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(latex) { mutableStateOf(false) }

    LaunchedEffect(latex, color, sizePx) {
        val rendered = MathRender.render(latex, sizePx, color.toArgb())
        if (rendered == null) failed = true else bitmap = rendered
    }

    Box(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed ->
                Text(
                    text = latex,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall.inScript(),
                    modifier =
                        Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                )

            bitmap != null ->
                Image(
                    bitmap = bitmap!!,
                    contentDescription = latex,
                    contentScale = ContentScale.Inside,
                )
        }
    }
}

private const val DISPLAY_SCALE = 1.15f
