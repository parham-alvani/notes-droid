package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The statuses beyond `[ ]` and `[x]`.
 *
 * Obsidian themes and the Tasks plugin use any single character: `[>]`
 * forwarded, `[!]` important, `[?]` a question, `[<]` scheduled. The Tasks
 * plugin's rule is that anything it does not know is still a thing to do --
 * only `x` is done and `-` is cancelled -- and these showed as list items with
 * `[>]` printed in front of them.
 */
class TaskStatusTest {
    private fun item(line: String): MdListItem =
        MarkdownParser
            .parseNote(line)
            .blocks
            .filterIsInstance<MdBlock.ListBlock>()
            .single()
            .items
            .single()

    private fun MdListItem.text(): String =
        blocks.filterIsInstance<MdBlock.Paragraph>().joinToString(" ") { plainText(it.inlines).trim() }

    @Test
    fun `any single character is a status, and the marker leaves the text`() {
        for (status in """><!?*"ibS~fkludpcI""") {
            val item = item("- [$status] call the bank")
            assertThat(item.task).isEqualTo(TaskState.UNCHECKED)
            assertThat(item.status).isEqualTo(status)
            assertThat(item.text()).isEqualTo("call the bank")
        }
    }

    @Test
    fun `the four known states are unchanged`() {
        assertThat(item("- [ ] a").task).isEqualTo(TaskState.UNCHECKED)
        assertThat(item("- [x] a").task).isEqualTo(TaskState.CHECKED)
        assertThat(item("- [-] a").task).isEqualTo(TaskState.CANCELLED)
        assertThat(item("- [/] a").task).isEqualTo(TaskState.IN_PROGRESS)
        assertThat(item("- [ ] a").status).isEqualTo(' ')
        assertThat(item("- [x] a").status).isEqualTo('x')
    }

    @Test
    fun `a link in square brackets is not a status`() {
        val item = item("- [see](https://example.com) the docs")
        assertThat(item.task).isEqualTo(TaskState.NONE)
    }

    @Test
    fun `an unknown status is an open task to the index as well`() {
        val tasks = TaskExtractor.extract(MarkdownParser.parseNote("- [>] forwarded\n- [!] urgent\n"))
        assertThat(tasks.map { it.text }).containsExactly("forwarded", "urgent").inOrder()
        assertThat(tasks.all { it.isOpen }).isTrue()
    }

    @Test
    fun `an unknown status can be completed and found again`() {
        assertThat(TaskLine.isTask("- [>] forwarded")).isTrue()
        assertThat(TaskLine.isOpen("- [>] forwarded")).isTrue()
        assertThat(TaskLine.complete("- [>] forwarded", "2026-09-23")).isEqualTo("- [x] forwarded ✅ 2026-09-23")
        assertThat(TaskLine.indexedText("- [!] urgent")).isEqualTo("urgent")
        assertThat(TaskLine.isOpen("- [X] done")).isFalse()
        assertThat(TaskLine.isOpen("- [-] dropped")).isFalse()
    }
}
