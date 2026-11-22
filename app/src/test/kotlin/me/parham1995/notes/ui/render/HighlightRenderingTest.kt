package me.parham1995.notes.ui.render

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.ui.theme.markup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A highlight is a tint under the text, and what is inside it keeps its own
 * colours: bold its orange, italic the text's (`look.md` in notes-linux's
 * `docs/specs/`). On the solid fill of before, orange on amber did not read.
 */
@RunWith(RobolectricTestRunner::class)
class HighlightRenderingTest {
    private val dark = darkColorScheme(background = Color(0xFF323232))
    private val light = lightColorScheme(background = Color(0xFFFAF9F5))

    private fun annotated(
        markdown: String,
        colors: androidx.compose.material3.ColorScheme = dark,
    ): AnnotatedString {
        val inlines =
            MarkdownParser
                .parseNote(markdown)
                .blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .single()
                .inlines
        return annotate(inlines, colors, InlineActions(), emptySet()).first
    }

    @Test
    fun `bold and italic inside a highlight keep their colour and their shape`() {
        for (colors in listOf(dark, light)) {
            val text = annotated("==a **bold** and *it* word==", colors)
            val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.SemiBold }
            val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
            assertThat(text.text.substring(bold.start, bold.end)).isEqualTo("bold")
            assertThat(bold.item.color).isEqualTo(colors.markup.Strong)
            assertThat(italic.item.color).isEqualTo(Color.Unspecified)
        }
    }

    @Test
    fun `outside a highlight they keep their own colours`() {
        val text = annotated("**bold** and *it*")
        assertThat(
            text.spanStyles
                .single { it.item.fontWeight == FontWeight.SemiBold }
                .item.color,
        ).isEqualTo(dark.markup.Strong)
        // Italic is slanted, in the text's own colour.
        assertThat(
            text.spanStyles
                .single { it.item.fontStyle == FontStyle.Italic }
                .item.color,
        ).isEqualTo(Color.Unspecified)
    }

    @Test
    fun `a highlight inside bold is drawn over it`() {
        val text = annotated("***==both==***")
        val spans = text.spanStyles.filter { it.start == 0 && it.end == 4 }
        // Italic, then bold, then the highlight: a tint, with no colour of its own for the text.
        assertThat(spans.last().item.background).isNotEqualTo(Color.Unspecified)
        assertThat(spans.last().item.color).isEqualTo(Color.Unspecified)
        assertThat(spans.any { it.item.fontWeight == FontWeight.SemiBold }).isTrue()
        assertThat(spans.any { it.item.fontStyle == FontStyle.Italic }).isTrue()
    }
}
