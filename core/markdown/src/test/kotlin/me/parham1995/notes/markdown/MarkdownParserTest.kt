package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownParserTest {
    private fun parse(markdown: String): ParsedNote = BlockFlattener().flatten(MarkdownParser.parse(markdown))

    private fun ParsedNote.text(): String =
        blocks.filterIsInstance<MdBlock.Paragraph>().joinToString("\n") { it.inlines.flat() }

    // -- wikilinks --------------------------------------------------------

    @Test
    fun `wikilinks parse in all their shapes`() {
        val note = parse("See [[Target]], [[Other|an alias]] and [[Deep/Path#Some Heading|short]].")
        val links =
            note.blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .first()
                .inlines
                .wikiLinks()

        assertThat(links).hasSize(3)
        assertThat(links[0].target).isEqualTo("Target")
        assertThat(links[1].target).isEqualTo("Other")
        assertThat(links[1].alias).isEqualTo("an alias")
        assertThat(links[2].target).isEqualTo("Deep/Path")
        assertThat(links[2].heading).isEqualTo("Some Heading")
        assertThat(links[2].alias).isEqualTo("short")
    }

    @Test
    fun `an escaped pipe in a table cell is still the alias separator`() {
        // Inside a table the pipe is escaped to protect the table delimiter,
        // not to put a literal pipe in the link -- filenames cannot contain
        // one. CommonMark resolves the escape before the text reaches us.
        val note =
            parse(
                """
                | Link |
                | --- |
                | [[Target\|Alias]] |
                """.trimIndent(),
            )
        val links =
            note.blocks
                .filterIsInstance<MdBlock.Table>()
                .single()
                .rows
                .first()
                .first()
                .wikiLinks()
        assertThat(links).hasSize(1)
        assertThat(links[0].target).isEqualTo("Target")
        assertThat(links[0].alias).isEqualTo("Alias")
    }

    @Test
    fun `an ordinary markdown link is left alone`() {
        val note = parse("A [normal](https://example.com) link.")
        val inlines =
            note.blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .first()
                .inlines
        assertThat(inlines.wikiLinks()).isEmpty()
        assertThat(inlines.filterIsInstance<MdInline.Link>()).hasSize(1)
    }

    @Test
    fun `an unclosed double bracket does not swallow the document`() {
        val note = parse("Broken [[start and then more text\n\nA second paragraph.")
        assertThat(note.blocks.filterIsInstance<MdBlock.Paragraph>()).hasSize(2)
    }

    @Test
    fun `a same-file anchor has no target`() {
        val links =
            parse("Jump to [[#Open items]].")
                .blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .first()
                .inlines
                .wikiLinks()
        assertThat(links).hasSize(1)
        assertThat(links[0].isSameFile).isTrue()
        assertThat(links[0].heading).isEqualTo("Open items")
    }

    @Test
    fun `an image embed becomes an image block`() {
        val note = parse("![[uploads/photo.jpg|Some caption]]")
        val image = note.blocks.filterIsInstance<MdBlock.Image>().single()
        assertThat(image.path).isEqualTo("uploads/photo.jpg")
        assertThat(image.alt).isEqualTo("Some caption")
    }

    @Test
    fun `a non-image embed becomes an attachment, not a broken image`() {
        val note = parse("![[uploads/clip.mp4]]")
        assertThat(note.blocks.filterIsInstance<MdBlock.Attachment>()).hasSize(1)
        assertThat(note.blocks.filterIsInstance<MdBlock.Image>()).isEmpty()
    }

    // -- highlight and callouts -------------------------------------------

    @Test
    fun `double equals is a highlight and single equals is not`() {
        val note = parse("A ==highlighted== phrase, and a key=value pair.")
        val inlines =
            note.blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .first()
                .inlines
        assertThat(inlines.filterIsInstance<MdInline.Highlight>()).hasSize(1)
        assertThat(note.text()).contains("key=value")
    }

    @Test
    fun `a callout carries its kind and title`() {
        val note = parse("> [!warning] Mind this\n>\n> The body text.")
        val callout = note.blocks.filterIsInstance<MdBlock.Callout>().single()
        assertThat(callout.kind).isEqualTo(CalloutKind.WARNING)
        assertThat(callout.title.flat()).isEqualTo("Mind this")
        assertThat(
            callout.children
                .filterIsInstance<MdBlock.Paragraph>()
                .first()
                .inlines
                .flat(),
        ).isEqualTo("The body text.")
    }

    @Test
    fun `callout aliases map onto the same kind`() {
        assertThat(
            parse("> [!tldr] x")
                .blocks
                .filterIsInstance<MdBlock.Callout>()
                .single()
                .kind,
        ).isEqualTo(CalloutKind.ABSTRACT)
        assertThat(
            parse("> [!important] x")
                .blocks
                .filterIsInstance<MdBlock.Callout>()
                .single()
                .kind,
        ).isEqualTo(CalloutKind.TIP)
        // An unknown kind degrades to a note rather than losing the callout.
        assertThat(
            parse("> [!nonsense] x")
                .blocks
                .filterIsInstance<MdBlock.Callout>()
                .single()
                .kind,
        ).isEqualTo(CalloutKind.NOTE)
    }

    @Test
    fun `a callout whose body is glued to the header still splits`() {
        // Missing the blank `>` separator is the single most common malformed
        // callout. The header is the first line; the rest is body.
        val callout =
            parse("> [!note] The title\n> The body.")
                .blocks
                .filterIsInstance<MdBlock.Callout>()
                .single()
        assertThat(callout.title.flat()).isEqualTo("The title")
        assertThat(callout.children).isNotEmpty()
    }

    @Test
    fun `a collapsed callout is marked as such`() {
        assertThat(
            parse("> [!info]- Hidden\n>\n> body")
                .blocks
                .filterIsInstance<MdBlock.Callout>()
                .single()
                .collapsed,
        ).isTrue()
    }

    // -- the four parser traps --------------------------------------------

    @Test
    fun `a fenced block inside a callout does not desync the rest of the file`() {
        val note =
            parse(
                """
                > [!note] Setup
                >
                > ```bash
                > echo hello
                > ```
                
                After the callout.
                """.trimIndent(),
            )
        val callout = note.blocks.filterIsInstance<MdBlock.Callout>().single()
        assertThat(callout.children.filterIsInstance<MdBlock.CodeBlock>()).hasSize(1)
        // The paragraph after it must still be a paragraph, not swallowed code.
        assertThat(
            note.blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .last()
                .inlines
                .flat(),
        ).isEqualTo("After the callout.")
    }

    @Test
    fun `a four-backtick fence can contain a three-backtick fence`() {
        val note =
            parse(
                """
                ````markdown
                ```bash
                echo nested
                ```
                ````
                
                Still here.
                """.trimIndent(),
            )
        val code = note.blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertThat(code.code).contains("```bash")
        assertThat(
            note.blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .last()
                .inlines
                .flat(),
        ).isEqualTo("Still here.")
    }

    @Test
    fun `indented lines are not code blocks`() {
        // Obsidian treats these as lazy list continuations. CommonMark would
        // turn them into code and mangle the list.
        val note =
            parse(
                """
                - An item
                    that continues on the next line
                """.trimIndent(),
            )
        assertThat(note.blocks.filterIsInstance<MdBlock.CodeBlock>()).isEmpty()
        assertThat(note.blocks.filterIsInstance<MdBlock.ListBlock>()).hasSize(1)
    }

    @Test
    fun `a regex character class is not a footnote`() {
        val note = parse("Match with `[^:]+` and `[^\\s]*` in the pattern.")
        assertThat(note.text()).contains("[^:]+")
    }

    // -- maths -------------------------------------------------------------

    @Test
    fun `inline and display maths both parse`() {
        val note = parse("Given ${'$'}x${'$'} and ${'$'}y${'$'}:\n\n${'$'}${'$'}\n\\frac{a}{b}\n${'$'}${'$'}")
        assertThat(note.hasMath).isTrue()
        val inline =
            note.blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .flatMap { it.inlines }
                .filterIsInstance<MdInline.InlineMath>()
        assertThat(inline).hasSize(2)
        assertThat(note.blocks.filterIsInstance<MdBlock.MathBlock>()).hasSize(1)
    }

    @Test
    fun `a shell variable in a fence is not maths`() {
        // The single biggest false-positive source: hundreds of these exist in
        // a real vault and none of them are formulas.
        val note =
            parse(
                """
                ```bash
                echo ${'$'}HOME and ${'$'}PATH
                ```
                """.trimIndent(),
            )
        assertThat(note.hasMath).isFalse()
        assertThat(note.blocks.filterIsInstance<MdBlock.CodeBlock>()).hasSize(1)
    }

    @Test
    fun `a lone dollar in prose is left as text`() {
        val note = parse("It costs ${'$'}5 to enter.")
        assertThat(note.hasMath).isFalse()
        assertThat(note.text()).contains("${'$'}5")
    }

    // -- tables, tasks, mermaid, queries -----------------------------------

    @Test
    fun `tables keep their header, rows and alignment`() {
        val note =
            parse(
                """
                | Name | Count |
                | --- | ---: |
                | one | 1 |
                | two | 2 |
                """.trimIndent(),
            )
        val table = note.blocks.filterIsInstance<MdBlock.Table>().single()
        assertThat(table.header.map { it.flat() }).containsExactly("Name", "Count")
        assertThat(table.rows).hasSize(2)
        assertThat(table.alignments[1]).isEqualTo(MdAlign.END)
    }

    @Test
    fun `all four task states are recognised`() {
        val note =
            parse(
                """
                - [ ] open
                - [x] done
                - [-] cancelled
                - [/] in progress
                """.trimIndent(),
            )
        val states =
            note.blocks
                .filterIsInstance<MdBlock.ListBlock>()
                .single()
                .items
                .map { it.task }
        assertThat(states)
            .containsExactly(
                TaskState.UNCHECKED,
                TaskState.CHECKED,
                TaskState.CANCELLED,
                TaskState.IN_PROGRESS,
            ).inOrder()
    }

    @Test
    fun `a mermaid fence becomes its own block`() {
        val note = parse("```mermaid\ngraph TD\n  A --> B\n```")
        assertThat(note.hasMermaid).isTrue()
        assertThat(
            note.blocks
                .filterIsInstance<MdBlock.Mermaid>()
                .single()
                .code,
        ).contains("graph TD")
    }

    @Test
    fun `dynamic queries are labelled rather than dumped on the reader`() {
        val note = parse("```dataview\nLIST FROM #x\n```")
        val unsupported = note.blocks.filterIsInstance<MdBlock.Unsupported>().single()
        assertThat(unsupported.label).contains("Dataview")
    }

    // -- direction and front matter ---------------------------------------

    @Test
    fun `direction is detected per block`() {
        val note = parse("English paragraph.\n\nسلام دنیا\n\nBack to English.")
        val paragraphs = note.blocks.filterIsInstance<MdBlock.Paragraph>()
        assertThat(paragraphs[0].direction).isEqualTo(MdDirection.LTR)
        assertThat(paragraphs[1].direction).isEqualTo(MdDirection.RTL)
        assertThat(paragraphs[2].direction).isEqualTo(MdDirection.LTR)
        assertThat(note.isRtl).isTrue()
    }

    @Test
    fun `front matter is captured and kept out of the body`() {
        val note = parse("---\ntitle: A Note\ndirection: rtl\n---\n\nBody text.")
        assertThat(note.frontMatter["title"]).isEqualTo("A Note")
        assertThat(note.frontMatter["direction"]).isEqualTo("rtl")
        assertThat(note.text()).isEqualTo("Body text.")
    }

    @Test
    fun `headings carry their level and scroll target`() {
        val note = parse("# Title\n\nsome text\n\n## Section\n\nmore")
        assertThat(note.headings.map { it.level }).containsExactly(1, 2).inOrder()
        assertThat(note.title).isEqualTo("Title")
        // The block index is what an outline tap scrolls to.
        assertThat(note.blocks[note.headings[1].blockIndex]).isInstanceOf(MdBlock.Heading::class.java)
    }

    @Test
    fun `a prose line break is honoured but one inside code is literal`() {
        val prose = parse("first<br>second")
        assertThat(
            prose.blocks
                .filterIsInstance<MdBlock.Paragraph>()
                .single()
                .inlines,
        ).contains(MdInline.LineBreak)

        val code = parse("```html\n<br>\n```")
        assertThat(
            code.blocks
                .filterIsInstance<MdBlock.CodeBlock>()
                .single()
                .code,
        ).contains("<br>")
    }

    @Test
    fun `an attachment embedded by its bare name is resolved like an image`() {
        val note =
            MarkdownParser.parseNote("![[clip.mp4]]\n\n![[photo.png]]") { target ->
                "uploads/$target"
            }

        assertThat(
            note.blocks
                .filterIsInstance<MdBlock.Attachment>()
                .single()
                .path,
        ).isEqualTo("uploads/clip.mp4")
        assertThat(
            note.blocks
                .filterIsInstance<MdBlock.Image>()
                .single()
                .path,
        ).isEqualTo("uploads/photo.png")
    }
}

private fun List<MdInline>.flat(): String =
    joinToString("") {
        when (it) {
            is MdInline.Text -> it.text
            is MdInline.Code -> it.code
            is MdInline.Emphasis -> it.children.flat()
            is MdInline.Strong -> it.children.flat()
            is MdInline.Strikethrough -> it.children.flat()
            is MdInline.Highlight -> it.children.flat()
            is MdInline.Link -> it.children.flat()
            is MdInline.WikiLink -> it.display
            is MdInline.InlineMath -> it.latex
            is MdInline.Tag -> "#" + it.name
            MdInline.LineBreak, MdInline.SoftBreak -> " "
        }
    }

private fun List<MdInline>.wikiLinks(): List<MdInline.WikiLink> = filterIsInstance<MdInline.WikiLink>()
