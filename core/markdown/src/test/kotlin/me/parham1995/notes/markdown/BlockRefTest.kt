package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockRefTest {
    private fun parse(markdown: String): ParsedNote = MarkdownParser.parseNote(markdown)

    private fun MdBlock.text(): String =
        when (this) {
            is MdBlock.Paragraph -> plainText(inlines)
            is MdBlock.Heading -> text
            is MdBlock.ListBlock -> items.joinToString("|") { item -> item.blocks.joinToString(" ") { it.text() } }
            else -> toString()
        }

    @Test
    fun `a trailing block id is hidden and remembered`() {
        val note = parse("First paragraph.\n\nThe quoted one. ^quote-1\n\nLast.")

        assertThat(note.blocks.map { it.text() }).containsExactly("First paragraph.", "The quoted one.", "Last.")
        assertThat(note.blockRefs).containsExactly("quote-1", 1)
        assertThat(note.blockTargets.getValue("quote-1").text()).isEqualTo("The quoted one.")
    }

    @Test
    fun `a block id is a position, not an id, after a list`() {
        // Every item of the list takes ids, so an id would run ahead here.
        val note = parse("- one\n- two\n- three\n\nAfter the list. ^after")
        assertThat(note.blockRefs.getValue("after")).isEqualTo(1)
        assertThat(note.blocks[1].text()).isEqualTo("After the list.")
    }

    @Test
    fun `a list item's id names that item, and embeds it alone`() {
        val note = parse("Intro.\n\n- one\n- two ^second\n- three")

        assertThat(note.blockRefs.getValue("second")).isEqualTo(1)
        val target = note.blockTargets.getValue("second") as MdBlock.ListBlock
        assertThat(target.items).hasSize(1)
        assertThat(target.text()).isEqualTo("two")
        // And the item itself no longer says it.
        assertThat(note.blocks[1].text()).isEqualTo("one|two|three")
    }

    @Test
    fun `an id on a line of its own names the block before it`() {
        val note = parse("| a | b |\n| - | - |\n| 1 | 2 |\n\n^table\n\nAfter.")

        assertThat(note.blocks).hasSize(2)
        assertThat(note.blocks[0]).isInstanceOf(MdBlock.Table::class.java)
        assertThat(note.blockRefs).containsExactly("table", 0)
    }

    @Test
    fun `an id on the line under a paragraph belongs to that paragraph`() {
        val note = parse("Some words\n^under")
        assertThat(note.blocks.single().text()).isEqualTo("Some words")
        assertThat(note.blockRefs).containsExactly("under", 0)
    }

    @Test
    fun `a task keeps its dates when a block id follows them`() {
        val note = parse("- [ ] pay the bill 📅 2024-05-01 ^bill")
        val task = TaskExtractor.extract(note).single()
        assertThat(task.text).isEqualTo("pay the bill")
        assertThat(task.due).isEqualTo("2024-05-01")
        assertThat(note.blockRefs).containsKey("bill")
    }

    @Test
    fun `a caret inside a sentence is not an id`() {
        val note = parse("x^2 and 2^10 ^ nothing")
        assertThat(note.blockRefs).isEmpty()
        assertThat(note.blocks.single().text()).isEqualTo("x^2 and 2^10 ^ nothing")
    }

    @Test
    fun `transcluding a block id draws that block`() {
        val note = parse("# H\n\nOne. ^a\n\nTwo.")
        val section = Transclusion.section(note.blocks, "^a", note.blockTargets)!!
        assertThat(section.map { it.text() }).containsExactly("One.")
        assertThat(Transclusion.section(note.blocks, "^missing", note.blockTargets)).isNull()
    }

    // -- heading paths ------------------------------------------------------

    private val chapters =
        parse(
            """
            # One
            ## Notes
            first notes
            # Two
            ## Notes
            second notes
            ### Deep
            deep text
            """.trimIndent(),
        )

    @Test
    fun `a heading path finds the child under its parent`() {
        val section = Transclusion.section(chapters.blocks, "Two#Notes")!!
        assertThat(section.map { it.text() }).containsExactly("Notes", "second notes", "Deep", "deep text").inOrder()
        assertThat(Transclusion.section(chapters.blocks, "Two#Deep")!!.map { it.text() })
            .containsExactly("Deep", "deep text")
    }

    @Test
    fun `a heading path that goes nowhere is nothing`() {
        assertThat(Transclusion.section(chapters.blocks, "One#Deep")).isNull()
        assertThat(Transclusion.section(chapters.blocks, "Three#Notes")).isNull()
    }

    @Test
    fun `a heading with a hash in it is found whole`() {
        val note = parse("# C# tips\n\nuse var")
        assertThat(Transclusion.section(note.blocks, "C# tips")!!.map { it.text() }).contains("use var")
    }

    @Test
    fun `heading paths over plain levels and texts`() {
        val headings = listOf(1 to "A", 2 to "X", 1 to "B", 2 to "X", 3 to "Y")
        assertThat(HeadingPath.find(headings, "X")).isEqualTo(1)
        assertThat(HeadingPath.find(headings, "B#X")).isEqualTo(3)
        assertThat(HeadingPath.find(headings, "b # x # y")).isEqualTo(4)
        assertThat(HeadingPath.find(headings, "A#Y")).isNull()
    }
}
