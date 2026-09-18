package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskExtractorTest {
    private fun tasks(markdown: String) = TaskExtractor.extract(MarkdownParser.parseNote(markdown))

    @Test
    fun `finds an open task and its text without the metadata`() {
        val task = tasks("- [ ] Deploy the thing ➕ 2026-09-01 ⏳ 2026-09-18").single()

        assertThat(task.text).isEqualTo("Deploy the thing")
        assertThat(task.state).isEqualTo(TaskState.UNCHECKED)
        assertThat(task.scheduled).isEqualTo("2026-09-18")
        assertThat(task.isOpen).isTrue()
    }

    @Test
    fun `reads every state this vault uses`() {
        val found =
            tasks(
                """
                - [ ] open
                - [x] done ✅ 2026-09-10
                - [-] cancelled ❌ 2026-09-11
                - [/] in progress
                """.trimIndent(),
            )

        assertThat(found.map { it.state })
            .containsExactly(
                TaskState.UNCHECKED,
                TaskState.CHECKED,
                TaskState.CANCELLED,
                TaskState.IN_PROGRESS,
            ).inOrder()
        assertThat(found.map { it.isOpen }).containsExactly(true, false, false, true).inOrder()
        assertThat(found[1].done).isEqualTo("2026-09-10")
        assertThat(found[2].cancelled).isEqualTo("2026-09-11")
    }

    @Test
    fun `the scheduled date is what makes a task answerable here`() {
        // The convention in this vault is to schedule work rather than promise
        // it, so reading only the due date would show almost nothing.
        assertThat(tasks("- [ ] a ⏳ 2026-09-18").single().actionableOn).isEqualTo("2026-09-18")
        // But an explicit due date is the stronger claim and wins.
        assertThat(tasks("- [ ] a ⏳ 2026-09-18 📅 2026-09-20").single().actionableOn).isEqualTo("2026-09-20")
        assertThat(tasks("- [ ] a").single().actionableOn).isNull()
    }

    @Test
    fun `a task is attributed to the heading above it`() {
        val found =
            tasks(
                """
                ## Apollo

                - [ ] first

                ## Gemini

                - [ ] second
                """.trimIndent(),
            )

        assertThat(found.map { it.section }).containsExactly("Apollo", "Gemini").inOrder()
    }

    @Test
    fun `a sub-task counts in its own right`() {
        // The vault's own rule: a sub-task is a real task, never flattened
        // into its parent.
        val found =
            tasks(
                """
                - [ ] parent
                    - [ ] child ⏳ 2026-09-19
                """.trimIndent(),
            )

        assertThat(found).hasSize(2)
        assertThat(found.map { it.text }).containsExactly("parent", "child").inOrder()
        assertThat(found[1].scheduled).isEqualTo("2026-09-19")
    }

    @Test
    fun `a parent's text does not swallow its children`() {
        val parent = tasks("- [ ] parent\n    - [ ] child").first()

        assertThat(parent.text).isEqualTo("parent")
    }

    @Test
    fun `a task inside a callout is still a task`() {
        val found =
            tasks(
                """
                > [!todo] This week
                >
                > - [ ] buried but real
                """.trimIndent(),
            )

        assertThat(found.map { it.text }).containsExactly("buried but real")
    }

    @Test
    fun `a plain list item is not a task`() {
        assertThat(tasks("- just a bullet\n- another")).isEmpty()
    }

    @Test
    fun `links in a task read as their text`() {
        val task = tasks("- [ ] finish [[Some Note|the writeup]] today").single()

        assertThat(task.text).isEqualTo("finish the writeup today")
    }

    @Test
    fun `every task carries the block it came from, so a tap can scroll`() {
        val found =
            tasks(
                """
                # Title

                Some prose.

                - [ ] a
                """.trimIndent(),
            )

        assertThat(found.single().blockIndex).isGreaterThan(0)
    }
}
