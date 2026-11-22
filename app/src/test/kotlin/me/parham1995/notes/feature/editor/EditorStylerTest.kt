package me.parham1995.notes.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.CalloutKind
import me.parham1995.notes.ui.theme.DaylightAccents
import me.parham1995.notes.ui.theme.DaylightMarkup
import me.parham1995.notes.ui.theme.DaylightScheme
import me.parham1995.notes.ui.theme.EditorLook
import me.parham1995.notes.ui.theme.NazAccents
import me.parham1995.notes.ui.theme.NazMarkup
import me.parham1995.notes.ui.theme.NazScheme
import me.parham1995.notes.ui.theme.NazSoftAccents
import me.parham1995.notes.ui.theme.NazSoftMarkup
import me.parham1995.notes.ui.theme.NazSoftScheme
import me.parham1995.notes.ui.theme.editorLook
import org.junit.Test

/**
 * The editor's look as `look.md` (notes-linux's `docs/specs/`, "Android: the
 * editor") has it: sizes, weights, italic, line height, highlight, list
 * markers, the callout, the quote, the current line, and the marks of one
 * element at a time. Each is asked of the styled text, so none needs a window.
 */
class EditorStylerTest {
    private val naz = NazScheme.editorLook(SIZE)

    private fun draw(
        text: String,
        caret: Int? = null,
        look: EditorLook = naz,
        livePreview: Boolean = true,
    ): EditorDrawn {
        // With no caret asked for, it is on a last line of its own, away from everything.
        val note = if (caret == null) "$text\n" else text
        return EditorStyler().draw(note, TextRange(caret ?: note.length), look, livePreview)
    }

    /** The style of the character [of] first is at, every span over it merged in order. */
    private fun EditorDrawn.styleAt(of: String): SpanStyle {
        val at = text.text.indexOf(of)
        check(at >= 0) { "$of is not in the text" }
        return text.spanStyles.filter { at >= it.start && at < it.end }.fold(
            SpanStyle(),
        ) { all, next -> all.merge(next.item) }
    }

    private fun EditorDrawn.lineHeightAt(of: String): Float {
        val at = text.text.indexOf(of)
        return text.paragraphStyles
            .single { at >= it.start && at < it.end }
            .item.lineHeight.value
    }

    /** The characters that take room, as a person sees the line. */
    private fun EditorDrawn.seen(): String =
        text.text
            .filterIndexed { index, _ ->
                text.spanStyles.none { index >= it.start && index < it.end && it.item.fontSize == 0.sp }
            }.replace(BREAK, "\n")
            .trimEnd('\n')

    /** `aHeadingInTheEditorIs1Point6TimesTheTextSizeAtLevel1`: the spec's own case. */
    @Test
    fun aHeadingInTheEditorIs1Point6TimesTheTextSizeAtLevel1() {
        assertThat(draw("# Title").styleAt("Title").fontSize.value).isWithin(TOLERANCE).of(SIZE * 1.6f)
    }

    @Test
    fun `headings have their level's size, colour and weight`() {
        val drawn = draw("# one\n## two\n### three\n#### four\n##### five\n###### six")
        val sizes = listOf("one", "two", "three", "four", "five", "six").map { drawn.styleAt(it).fontSize.value / SIZE }
        listOf(1.6f, 1.35f, 1.12f, 1f, 1f, 0.875f).forEachIndexed { level, scale ->
            assertThat(sizes[level]).isWithin(TOLERANCE).of(scale)
        }
        assertThat(drawn.styleAt("one").color).isEqualTo(NazMarkup.Heading1)
        assertThat(drawn.styleAt("two").color).isEqualTo(NazMarkup.Heading2)
        assertThat(drawn.styleAt("three").color).isEqualTo(NazMarkup.Heading3)
        assertThat(drawn.styleAt("four").color).isEqualTo(NazMarkup.Heading4)
        // One weight, and a lighter one for levels 5 and 6.
        assertThat(drawn.styleAt("one").fontWeight).isEqualTo(FontWeight(650))
        assertThat(drawn.styleAt("four").fontWeight).isEqualTo(FontWeight(650))
        assertThat(drawn.styleAt("five").fontWeight).isEqualTo(FontWeight(600))
        assertThat(drawn.styleAt("six").fontWeight).isEqualTo(FontWeight(600))
    }

    @Test
    fun `bold is 650 in the strong colour and italic is slanted in the text's colour`() {
        val drawn = draw("some **bold** and *slanted* text")
        assertThat(drawn.styleAt("bold").fontWeight).isEqualTo(FontWeight(650))
        assertThat(drawn.styleAt("bold").color).isEqualTo(NazMarkup.Strong)
        assertThat(drawn.styleAt("slanted").fontStyle).isEqualTo(FontStyle.Italic)
        // No colour of its own: it is drawn in the text's.
        assertThat(drawn.styleAt("slanted").color).isEqualTo(Color.Unspecified)
    }

    @Test
    fun `a line is 1_65 times its size high, and 2 times when it reads right to left`() {
        val drawn = draw("plain\n# Title\nیادداشت")
        assertThat(drawn.lineHeightAt("plain")).isWithin(TOLERANCE).of(SIZE * 1.65f)
        assertThat(drawn.lineHeightAt("Title")).isWithin(TOLERANCE).of(SIZE * 1.6f * 1.65f)
        assertThat(drawn.lineHeightAt("یادداشت")).isWithin(TOLERANCE).of(SIZE * 2f)
    }

    @Test
    fun `a highlight is its colour at 32 percent under text that keeps its colours`() {
        val drawn =
            draw("==plain== ==🔴red== ==🟠orange== ==🟡yellow== ==🟢green== ==🔵blue== ==🟣purple== ==a **b**==")
        val fills =
            listOf(
                "plain",
                "red",
                "orange",
                "yellow",
                "green",
                "blue",
                "purple",
            ).map { drawn.styleAt(it).background }
        assertThat(fills)
            .containsExactly(
                NazAccents.vividYellow.copy(alpha = 0.32f),
                NazAccents.red.copy(alpha = 0.32f),
                NazAccents.orange.copy(alpha = 0.32f),
                NazAccents.vividYellow.copy(alpha = 0.32f),
                NazAccents.springGreen.copy(alpha = 0.32f),
                NazAccents.blue.copy(alpha = 0.32f),
                NazAccents.purple.copy(alpha = 0.32f),
            ).inOrder()
        assertThat(drawn.styleAt("plain").color).isEqualTo(Color.Unspecified)
        // Bold inside a highlight is still the strong colour, on the tint.
        assertThat(drawn.styleAt("b**").color).isEqualTo(NazMarkup.Strong)
        assertThat(drawn.styleAt("b**").background).isEqualTo(NazAccents.vividYellow.copy(alpha = 0.32f))
    }

    @Test
    fun `list markers are one colour at every level`() {
        val drawn = draw("- one\n    * two\n        1. three")
        val colours = listOf("- one", "* two", "1. three").map { drawn.styleAt(it).color }
        assertThat(colours.toSet()).containsExactly(NazMarkup.ListMarker)
    }

    @Test
    fun `the marks of one element take no room until the caret is in it`() {
        val text = "# Title\n\nsome **bold** and ==marked== text"
        assertThat(draw(text).seen()).isEqualTo("Title\n\nsome bold and marked text")
        // The caret in "bold": its marks show, the highlight's and the heading's do not.
        assertThat(
            draw(text, caret = text.indexOf("bold") + 1).seen(),
        ).isEqualTo("Title\n\nsome **bold** and marked text")
        // The caret on the heading's line: its marks show.
        assertThat(draw(text, caret = 3).seen()).isEqualTo("# Title\n\nsome bold and marked text")
        // A mark that shows is in the faint colour.
        assertThat(draw(text, caret = 3).styleAt("# ").color).isEqualTo(naz.faint)
    }

    @Test
    fun `with live preview off every mark shows`() {
        val text = "# Title\n\nsome **bold** text\n> [!tip] t"
        assertThat(draw(text, livePreview = false).seen()).isEqualTo(text)
        // The colours of part 1 stay.
        assertThat(draw(text, livePreview = false).styleAt("bold").color).isEqualTo(NazMarkup.Strong)
    }

    @Test
    fun `the drawn text is the note's, with each line break drawn as the end of a paragraph`() {
        val note = "a\n\n# b\n"
        val drawn = EditorStyler().draw(note, TextRange(0), naz)
        assertThat(drawn.text.text).isEqualTo(note.replace("\n", BREAK) + BREAK)
        // One paragraph a line, and they cover the text with no gap.
        val paragraphs = drawn.text.paragraphStyles
        assertThat(paragraphs.map { it.start to it.end }).containsExactly(0 to 2, 2 to 3, 3 to 7, 7 to 8).inOrder()
    }

    @Test
    fun `a callout is one panel in its kind's colour, and a quote a bar`() {
        val drawn = draw("> [!warning] Mind\n> the gap\n\n> plain\n> quote\n\ntext")
        val callout = drawn.decor.filterIsInstance<EditorDecor.Callout>().single()
        assertThat(callout.kind).isEqualTo(CalloutKind.WARNING)
        assertThat(callout.start).isEqualTo(0)
        assertThat(callout.end).isEqualTo("> [!warning] Mind\n> the gap".length)
        // It has a title, so no name is drawn.
        assertThat(callout.name).isNull()
        assertThat(drawn.styleAt("Mind").color).isEqualTo(NazAccents.orange)
        assertThat(drawn.styleAt("Mind").fontWeight).isEqualTo(FontWeight(650))
        assertThat(naz.calloutColor(CalloutKind.WARNING)).isEqualTo(NazAccents.orange)

        val quote = drawn.decor.filterIsInstance<EditorDecor.Quote>().single()
        assertThat(
            drawn.text.text
                .substring(quote.start, quote.end)
                .replace(BREAK, "\n"),
        ).isEqualTo("> plain\n> quote")
        assertThat(drawn.seen()).isEqualTo("Mind\nthe gap\n\nplain\nquote\n\ntext")
    }

    @Test
    fun `a callout with no title reads its kind's name, which is drawn and not written`() {
        val away = draw("> [!tip]\n> body")
        assertThat(
            away.decor
                .filterIsInstance<EditorDecor.Callout>()
                .single()
                .name,
        ).isEqualTo("Tip")
        assertThat(away.text.text).doesNotContain("Tip")
        // With the caret on the line its marks show, and the name is not drawn over them.
        val on = draw("> [!tip]\n> body", caret = 2)
        assertThat(
            on.decor
                .filterIsInstance<EditorDecor.Callout>()
                .single()
                .name,
        ).isNull()
        assertThat(on.seen()).isEqualTo("> [!tip]\nbody")
    }

    @Test
    fun `the caret's line is marked, and a selection has none`() {
        val text = "one\ntwo\nthree"
        val drawn = draw(text, caret = 5)
        assertThat(drawn.decor).containsExactly(EditorDecor.CurrentLine(4, 7))
        assertThat(naz.currentLine).isEqualTo(naz.text.copy(alpha = 0.045f))
        val selected = EditorStyler().draw(text, TextRange(1, 5), naz)
        assertThat(selected.decor).isEmpty()
    }

    @Test
    fun `each palette has its own colours`() {
        val daylight = DaylightScheme.editorLook(SIZE)
        val soft = NazSoftScheme.editorLook(SIZE)
        assertThat(naz.markup).isSameInstanceAs(NazMarkup)
        assertThat(naz.accents).isSameInstanceAs(NazAccents)
        assertThat(daylight.markup).isSameInstanceAs(DaylightMarkup)
        assertThat(daylight.accents).isSameInstanceAs(DaylightAccents)
        assertThat(soft.markup).isSameInstanceAs(NazSoftMarkup)
        assertThat(soft.accents).isSameInstanceAs(NazSoftAccents)

        for (look in listOf(naz, daylight, soft)) {
            val drawn = draw("# Title\n**bold** ==🔴late==\n> [!tip] t", look = look)
            assertThat(drawn.styleAt("Title").color).isEqualTo(look.markup.Heading1)
            assertThat(drawn.styleAt("bold").color).isEqualTo(look.markup.Strong)
            assertThat(drawn.styleAt("late").background).isEqualTo(look.accents.red.copy(alpha = 0.32f))
            assertThat(drawn.styleAt("t" + BREAK).color).isEqualTo(look.accents.chartreuse)
        }
        // The caret and the quote's bar are the accent of each.
        assertThat(naz.accent).isEqualTo(NazScheme.primary)
        assertThat(daylight.accent).isEqualTo(DaylightScheme.primary)
        assertThat(soft.accent).isEqualTo(NazSoftScheme.primary)
        assertThat(soft.accent).isNotEqualTo(naz.accent)
    }

    @Test
    fun `a key makes its own line again and no other`() {
        val note = (1..400).joinToString("\n") { "line $it with **bold** and ==marks==" }
        val styler = EditorStyler()
        val at = note.indexOf("line 200") + 4
        styler.draw(note, TextRange(at), naz)
        assertThat(styler.lastMade).isEqualTo(400)

        val typed = note.substring(0, at) + "x" + note.substring(at)
        val drawn = styler.draw(typed, TextRange(at + 1), naz)
        assertThat(styler.lastMade).isEqualTo(1)
        assertThat(drawn.text.text.length).isEqualTo(typed.length + 1)

        // The caret goes to another line: the line it left and the line it is on.
        styler.draw(typed, TextRange(3), naz)
        assertThat(styler.lastMade).isEqualTo(2)
        // What is made that way is what a new styler makes of the same note.
        val again: AnnotatedString = styler.draw(typed, TextRange(3), naz).text
        assertThat(again).isEqualTo(EditorStyler().draw(typed, TextRange(3), naz).text)
    }

    private companion object {
        const val SIZE = 15f
        const val TOLERANCE = 0.001f
        const val BREAK = "​"
    }
}
