package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How the editor reads a line: `look.md` in notes-linux's `docs/specs/`. */
class EditorMarkupTest {
    private fun read(
        text: String,
        before: EditorState = EditorState(),
    ) = EditorMarkup.line(text, before).line

    private fun note(text: String): List<EditorLine> {
        val document = EditorDocument()
        document.update(text)
        return List(document.size) { document.line(it) }
    }

    private fun EditorLine.said(
        text: String,
        style: EditorStyle,
    ) = spans.filter { it.style == style }.map { text.substring(it.start, it.end) }

    /** The text of the line with its hidden marks taken out, as it is seen with the caret at [caret]. */
    private fun seen(
        text: String,
        caret: Int?,
    ): String {
        val hidden = read(text).hidden(caret?.let { EditorRange(it, it) })
        return text.filterIndexed { index, _ -> hidden.none { index >= it.start && index < it.end } }
    }

    @Test
    fun `a heading has its level and its marks are the line's`() {
        val text = "## Plan for *today*"
        val line = read(text)
        assertThat(line.kind).isEqualTo(EditorLineKind.HEADING)
        assertThat(line.level).isEqualTo(2)
        assertThat(line.said(text, EditorStyle.HEADING)).containsExactly("Plan for *today*")
        assertThat(line.said(text, EditorStyle.EMPHASIS)).containsExactly("today")
        assertThat(line.lineMarks).containsExactly(EditorRange(0, 3))
        assertThat(read("###### six").level).isEqualTo(6)
    }

    @Test
    fun `a hash with no space after it is a tag, not a heading`() {
        val line = read("#garden and #12")
        assertThat(line.kind).isEqualTo(EditorLineKind.TEXT)
        assertThat(line.said("#garden and #12", EditorStyle.TAG)).containsExactly("#garden")
    }

    @Test
    fun `the marks of a heading show on the caret's line only`() {
        assertThat(seen("# Title", null)).isEqualTo("Title")
        assertThat(seen("# Title", 4)).isEqualTo("# Title")
    }

    @Test
    fun `the marks of one element show only while the caret is in it`() {
        val text = "a **bold** and ==marked== b"
        // Not on the line: every mark is hidden.
        assertThat(seen(text, null)).isEqualTo("a bold and marked b")
        // On the line and in neither element.
        assertThat(seen(text, 0)).isEqualTo("a bold and marked b")
        // In the bold, both ends counted: before its first mark and after its last.
        assertThat(seen(text, 2)).isEqualTo("a **bold** and marked b")
        assertThat(seen(text, 6)).isEqualTo("a **bold** and marked b")
        assertThat(seen(text, 10)).isEqualTo("a **bold** and marked b")
        assertThat(seen(text, 11)).isEqualTo("a bold and marked b")
        // In the highlight.
        assertThat(seen(text, 20)).isEqualTo("a bold and ==marked== b")
    }

    @Test
    fun `a selection that touches an element shows its marks`() {
        val text = "a **bold** and `code`"
        val hidden = read(text).hidden(EditorRange(0, 2))
        assertThat(hidden).containsExactly(EditorRange(15, 16), EditorRange(20, 21))
        assertThat(read(text).hidden(EditorRange(0, 1))).hasSize(4)
    }

    @Test
    fun `an element inside another shows its own marks with the caret in it`() {
        val text = "**a *b* c**"
        assertThat(seen(text, 3)).isEqualTo("**a b c**")
        assertThat(seen(text, 5)).isEqualTo("**a *b* c**")
        assertThat(read(text).said(text, EditorStyle.STRONG)).containsExactly("a *b* c")
        assertThat(read(text).said(text, EditorStyle.EMPHASIS)).containsExactly("b")
    }

    @Test
    fun `three stars are bold and italic`() {
        val text = "***both***"
        assertThat(read(text).said(text, EditorStyle.STRONG)).containsExactly("*both*")
        assertThat(read(text).said(text, EditorStyle.EMPHASIS)).containsExactly("both")
        assertThat(seen(text, null)).isEqualTo("both")
    }

    @Test
    fun `marks that do not close are text`() {
        assertThat(read("a ** b and 2 * 3 * 4").elements).isEmpty()
        assertThat(read("snake_case_name").elements).isEmpty()
        assertThat(read("a == b").elements).isEmpty()
        assertThat(read("""\*not italic\*""").elements).isEmpty()
    }

    @Test
    fun `a highlight has its colour and the emoji is one of its marks`() {
        val text = "==🔴 late== and ==plain== and ==🔵blue=="
        val line = read(text)
        val highlights = line.spans.filter { it.style == EditorStyle.HIGHLIGHT }
        assertThat(highlights.map { it.color })
            .containsExactly(HighlightColor.RED, null, HighlightColor.BLUE)
            .inOrder()
        assertThat(
            highlights.map { text.substring(it.start, it.end) },
        ).containsExactly("late", "plain", "blue").inOrder()
        assertThat(seen(text, null)).isEqualTo("late and plain and blue")
    }

    @Test
    fun `code holds no other element`() {
        val text = "`**not bold**` and ``a ` b``"
        val line = read(text)
        assertThat(line.said(text, EditorStyle.CODE)).containsExactly("**not bold**", "a ` b")
        assertThat(line.said(text, EditorStyle.STRONG)).isEmpty()
        assertThat(seen(text, null)).isEqualTo("**not bold** and a ` b")
    }

    @Test
    fun `a wikilink shows its alias and a link its text`() {
        val text = "[[Garden plan|the plan]] [[Plain]] [site](https://example.com) ![[shot.png]]"
        val line = read(text)
        assertThat(line.said(text, EditorStyle.LINK)).containsExactly("the plan", "Plain", "site", "shot.png")
        assertThat(seen(text, null)).isEqualTo("the plan Plain site shot.png")
        assertThat(seen(text, 5)).isEqualTo("[[Garden plan|the plan]] Plain site shot.png")
    }

    @Test
    fun `a task's box and a footnote are not links`() {
        assertThat(read("- [ ] Buy [^1]").elements).isEmpty()
        assertThat(read("- [ ] Buy").said("- [ ] Buy", EditorStyle.LIST_MARKER)).containsExactly("-")
    }

    @Test
    fun `list markers are one style at every level`() {
        assertThat(read("- a").said("- a", EditorStyle.LIST_MARKER)).containsExactly("-")
        assertThat(read("    * b").said("    * b", EditorStyle.LIST_MARKER)).containsExactly("*")
        assertThat(read("12. c").said("12. c", EditorStyle.LIST_MARKER)).containsExactly("12.")
        assertThat(read("*not* a list").said("*not* a list", EditorStyle.LIST_MARKER)).isEmpty()
        assertThat(read("---").said("---", EditorStyle.RULE)).containsExactly("---")
    }

    @Test
    fun `a line is right to left by its first letter`() {
        assertThat(read("## یادداشت").rtl).isTrue()
        assertThat(read("- [ ] خرید").rtl).isTrue()
        assertThat(read("Plan یادداشت").rtl).isFalse()
    }

    /** `aCalloutHasItsKindsLook` of the spec. */
    @Test
    fun aCalloutHasItsKindsLook() {
        val lines = note("> [!warning] Mind\n> the gap\n\n> [!tip]\n\n> [!nonsense] x\n\n> plain\n\n> [!note]- x")
        assertThat(lines[0].callout).isEqualTo(EditorCallout(CalloutKind.WARNING, "Mind", titled = true))
        assertThat(lines[0].quote).isEqualTo(EditorQuote.CALLOUT)
        assertThat(lines[1].quote).isEqualTo(EditorQuote.CALLOUT)
        assertThat(lines[1].callout).isNull()
        assertThat(lines[2].quote).isEqualTo(EditorQuote.NONE)
        assertThat(lines[3].callout).isEqualTo(EditorCallout(CalloutKind.TIP, "Tip", titled = false))
        assertThat(lines[5].callout?.kind).isEqualTo(CalloutKind.NOTE)
        assertThat(lines[7].quote).isEqualTo(EditorQuote.QUOTE)
        assertThat(lines[7].callout).isNull()
        assertThat(lines[9].callout).isEqualTo(EditorCallout(CalloutKind.NOTE, "x", titled = true))
    }

    @Test
    fun `a callout's marks are the line's, and its body is read as text`() {
        assertThat(seen("> [!warning] Mind", null)).isEqualTo("Mind")
        assertThat(seen("> [!warning] Mind", 3)).isEqualTo("> [!warning] Mind")
        val body = EditorMarkup.line("> the **gap**", EditorState(quote = EditorQuote.CALLOUT)).line
        assertThat(body.said("> the **gap**", EditorStyle.STRONG)).containsExactly("gap")
        assertThat(body.lineMarks).containsExactly(EditorRange(0, 2))
    }

    @Test
    fun `a callout that is not on the first line of its run is a quote`() {
        val lines = note("> plain\n> [!tip] not one")
        assertThat(lines[1].quote).isEqualTo(EditorQuote.QUOTE)
        assertThat(lines[1].callout).isNull()
    }

    @Test
    fun `code, the front matter, math and comments hold no element`() {
        val lines =
            note("---\ntitle: **x**\n---\n```kotlin\nval a = **b**\n```\n$$\n**m**\n$$\n%%\n**c**\n%%\n**bold**")
        assertThat(lines.map { it.kind })
            .containsExactly(
                EditorLineKind.PLAIN,
                EditorLineKind.PLAIN,
                EditorLineKind.PLAIN,
                EditorLineKind.FENCE,
                EditorLineKind.CODE,
                EditorLineKind.FENCE,
                EditorLineKind.PLAIN,
                EditorLineKind.PLAIN,
                EditorLineKind.PLAIN,
                EditorLineKind.PLAIN,
                EditorLineKind.PLAIN,
                EditorLineKind.PLAIN,
                EditorLineKind.TEXT,
            ).inOrder()
        assertThat(lines.dropLast(1).flatMap { it.elements }).isEmpty()
        assertThat(lines.last().elements).hasSize(1)
    }

    @Test
    fun `a rule is not a front matter after the first line`() {
        val lines = note("text\n---\n**bold**")
        assertThat(lines[2].kind).isEqualTo(EditorLineKind.TEXT)
        assertThat(lines[2].elements).hasSize(1)
    }

    @Test
    fun `an unclosed fence runs to the note's end, and a longer fence holds a shorter one`() {
        assertThat(note("```\na\nb").map { it.kind })
            .containsExactly(EditorLineKind.FENCE, EditorLineKind.CODE, EditorLineKind.CODE)
        assertThat(note("````\n```\n````\nx").map { it.kind })
            .containsExactly(EditorLineKind.FENCE, EditorLineKind.CODE, EditorLineKind.FENCE, EditorLineKind.TEXT)
    }

    @Test
    fun `a code block in a callout ends with the callout`() {
        val lines = note("> [!tip] Try\n> ```sh\n> ls **x**\nafter **bold**")
        assertThat(lines[1].kind).isEqualTo(EditorLineKind.FENCE)
        assertThat(lines[2].kind).isEqualTo(EditorLineKind.CODE)
        assertThat(lines[2].quote).isEqualTo(EditorQuote.CALLOUT)
        assertThat(lines[3].kind).isEqualTo(EditorLineKind.TEXT)
        assertThat(lines[3].elements).hasSize(1)
    }

    @Test
    fun `links, addresses, math and inline comments are read`() {
        val text = "<https://a.example> https://b.example/x. \$x^2\$ costs \$5 and \$6 %%hidden%%"
        val line = read(text)
        assertThat(line.said(text, EditorStyle.LINK)).containsExactly("https://a.example", "https://b.example/x.")
        assertThat(line.said(text, EditorStyle.MATH)).containsExactly("\$x^2\$")
        assertThat(line.said(text, EditorStyle.COMMENT)).containsExactly("%%hidden%%")
    }

    @Test
    fun `any line is read without a failure`() {
        val odd =
            listOf(
                "",
                " ",
                "*",
                "**",
                "***",
                "`",
                "``",
                "[",
                "[[",
                "[[]]",
                "[]()",
                "![",
                "==",
                "====",
                "~~",
                "$",
                "$$ ",
                "\\",
                "> ",
                ">",
                "#",
                "# ",
                "==🔴==",
                "[[a|]]",
                "<",
                "h",
                "_",
                "__a",
                "*a",
                "a*",
                "[a](",
                "%%",
            )
        for (text in odd) {
            val line = read(text)
            assertThat(line.length).isEqualTo(text.length)
            for (range in line.marks + line.spans.map { EditorRange(it.start, it.end) }) {
                assertThat(range.start).isAtLeast(0)
                assertThat(range.end).isAtMost(text.length)
                assertThat(range.start).isAtMost(range.end)
            }
        }
    }
}
