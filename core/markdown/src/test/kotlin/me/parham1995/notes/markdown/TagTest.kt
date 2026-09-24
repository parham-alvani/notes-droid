package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagTest {
    private fun parse(markdown: String): ParsedNote = MarkdownParser.parseNote(markdown)

    private fun tagsIn(markdown: String): List<String> = parse(markdown).tags

    private fun List<MdInline>.tags(): List<String> =
        flatMap { node ->
            when (node) {
                is MdInline.Tag -> listOf(node.name)
                is MdInline.Emphasis -> node.children.tags()
                is MdInline.Strong -> node.children.tags()
                else -> emptyList()
            }
        }

    @Test
    fun `an inline tag becomes a tag, with the text around it kept`() {
        val paragraph = parse("Filed under #idea for later.").blocks.single() as MdBlock.Paragraph
        assertThat(paragraph.inlines)
            .containsExactly(MdInline.Text("Filed under "), MdInline.Tag("idea"), MdInline.Text(" for later."))
            .inOrder()
    }

    @Test
    fun `nested tags keep their slashes`() {
        assertThat(tagsIn("#project/alpha and #project/beta/sub")).containsExactly("project/alpha", "project/beta/sub")
    }

    @Test
    fun `a number is not a tag`() {
        assertThat(tagsIn("See issue #1 and #2024, but #y2024 is fine")).containsExactly("y2024")
        assertThat(tagsIn("A date #2024/01 is not either")).isEmpty()
    }

    @Test
    fun `punctuation ends a tag`() {
        assertThat(tagsIn("It was an #idea. #list, #end/ #semi;colon")).containsExactly("idea", "list", "end", "semi")
    }

    @Test
    fun `a hash glued to a word is not a tag`() {
        assertThat(tagsIn("C#sharp and issue#12 and a**b**#c")).isEmpty()
    }

    @Test
    fun `code is never read for tags`() {
        assertThat(tagsIn("Use `#define` here\n\n```\n#include <x>\n```")).isEmpty()
    }

    @Test
    fun `a heading's own marker is not a tag, a tag in it is`() {
        val note = parse("# Title #draft\n\n#not-a-heading")
        assertThat(note.tags).containsExactly("draft", "not-a-heading")
        assertThat(note.headings.single().text).isEqualTo("Title #draft")
    }

    @Test
    fun `a link's fragment and a link's label are not tags`() {
        assertThat(tagsIn("[#label](https://example.com/#top) https://example.com/#frag [[Note#Heading]]")).isEmpty()
    }

    @Test
    fun `persian tags are tags`() {
        assertThat(tagsIn("یادداشت #ایده")).containsExactly("ایده")
    }

    @Test
    fun `tags inside lists, callouts and emphasis are found`() {
        val note = parse("- item #one\n\n> [!note] Title #two\n> body #three\n\n*#four*")
        assertThat(note.tags).containsExactly("one", "two", "three", "four")
    }

    @Test
    fun `a tag at the start of a line is a tag`() {
        assertThat(tagsIn("first line\n#second")).containsExactly("second")
        val paragraph = parse("first line\n#second").blocks.single() as MdBlock.Paragraph
        assertThat(paragraph.inlines.tags()).containsExactly("second")
    }

    @Test
    fun `front matter tags come first and duplicates are dropped ignoring case`() {
        val note = parse("---\ntags:\n  - Alpha\n  - '#beta'\n---\nBody #alpha #gamma")
        assertThat(note.tags).containsExactly("Alpha", "beta", "gamma").inOrder()
    }

    @Test
    fun `front matter tags as a flow list or a string`() {
        assertThat(tagsIn("---\ntags: [a, b/c]\n---\n")).containsExactly("a", "b/c")
        assertThat(tagsIn("---\ntags: a, b\n---\n")).containsExactly("a", "b")
        assertThat(tagsIn("---\ntag: one two\n---\n")).containsExactly("one", "two")
    }

    @Test
    fun `tags stay in a task's text`() {
        val task = TaskExtractor.extract(parse("- [ ] call back #work")).single()
        assertThat(task.text).isEqualTo("call back #work")
    }

    @Test
    fun `ancestry lists every parent`() {
        assertThat(Tags.ancestry("a/b/c")).containsExactly("a", "a/b", "a/b/c").inOrder()
    }
}
