package me.parham1995.notes.ui.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import me.parham1995.notes.markdown.MdInline
import me.parham1995.notes.ui.inScript

/**
 * Text with inline formatting, links, and formulas.
 *
 * Inline maths cannot be a text span, so each formula is rendered to a bitmap
 * and placed through [InlineTextContent]. Placeholders are sized from the
 * rendered bitmap once it exists, which is why the map is state-backed rather
 * than computed once.
 */
@Composable
fun RichText(
    inlines: List<MdInline>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    actions: InlineActions = InlineActions(),
    brokenLinks: Set<String> = emptySet(),
    textAlign: TextAlign? = null,
) {
    // Kept until what it is built from changes. The inlines and the set of
    // broken links are the note's own and keep their identity, and the actions
    // are remembered by the screen, so an unrelated recomposition -- a
    // keystroke in the find bar, a snackbar -- reuses the string.
    val colors = MaterialTheme.colorScheme
    val (annotated, formulas) =
        remember(inlines, actions, brokenLinks, colors) {
            annotate(inlines, colors, actions, brokenLinks)
        }
    val color = colors.onSurface
    val density = LocalDensity.current
    val sizePx = with(density) { style.fontSize.takeIf { it.isSpecified() }?.toPx() ?: DEFAULT_SIZE_PX }

    val rendered = remember(formulas, color, sizePx) { mutableStateMapOf<Int, ImageBitmap>() }

    LaunchedEffect(formulas, color, sizePx) {
        formulas.forEachIndexed { index, latex ->
            MathRender.render(latex, sizePx, color.toArgb())?.let { rendered[index] = it }
        }
    }

    val inlineContent =
        formulas
            .mapIndexed { index, latex ->
                val bitmap = rendered[index]
                val widthEm = bitmap?.let { it.width.toFloat() / sizePx } ?: (latex.length * FALLBACK_EM_PER_CHAR)
                val heightEm = bitmap?.let { it.height.toFloat() / sizePx } ?: 1.2f
                MATH_TAG_PREFIX + index to
                    InlineTextContent(
                        Placeholder(
                            width = TextUnit(widthEm, TextUnitType.Em),
                            height = TextUnit(heightEm, TextUnitType.Em),
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val image = rendered[index]
                            if (image != null) {
                                Image(bitmap = image, contentDescription = latex)
                            } else {
                                // Until it renders, show the source rather than
                                // a gap that reflows when the bitmap lands.
                                Text(
                                    latex,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall.inScript(),
                                )
                            }
                        }
                    }
            }.toMap()

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        inlineContent = inlineContent,
    )
}

private fun TextUnit.isSpecified(): Boolean = this != TextUnit.Unspecified

private const val DEFAULT_SIZE_PX = 42f
private const val FALLBACK_EM_PER_CHAR = 0.55f
