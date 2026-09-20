package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskVisibilityTest {
    private fun items(markdown: String): List<MdListItem> =
        MarkdownParser
            .parseNote(markdown)
            .blocks
            .filterIsInstance<MdBlock.ListBlock>()
            .flatMap { it.items }

    @Test
    fun `a ticked task with nothing under it can be hidden`() {
        val item = items("- [x] done ✅ 2026-09-20").single()

        assertThat(item.isFinishedAndEmpty()).isTrue()
    }

    @Test
    fun `a cancelled task counts as finished`() {
        assertThat(items("- [-] never mind").single().isFinishedAndEmpty()).isTrue()
    }

    @Test
    fun `an open task is never hidden`() {
        assertThat(items("- [ ] still to do").single().isFinishedAndEmpty()).isFalse()
        assertThat(items("- [/] halfway").single().isFinishedAndEmpty()).isFalse()
    }

    @Test
    fun `a ticked parent holding an open sub-task stays`() {
        val parent =
            items(
                """
                - [x] parent ✅ 2026-09-20
                    - [ ] child
                """.trimIndent(),
            ).first()

        assertThat(parent.task).isEqualTo(TaskState.CHECKED)
        assertThat(parent.isFinishedAndEmpty()).isFalse()
    }

    @Test
    fun `a ticked parent whose sub-tasks are all done can go`() {
        val parent =
            items(
                """
                - [x] parent ✅ 2026-09-20
                    - [x] child ✅ 2026-09-19
                """.trimIndent(),
            ).first()

        assertThat(parent.isFinishedAndEmpty()).isTrue()
    }

    @Test
    fun `an ordinary list item is not a task and is never hidden`() {
        assertThat(items("- just a bullet").single().isFinishedAndEmpty()).isFalse()
    }
}
