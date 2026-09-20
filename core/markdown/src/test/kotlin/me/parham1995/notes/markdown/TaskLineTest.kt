package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskLineTest {
    @Test
    fun `ticks an open task and dates it`() {
        assertThat(TaskLine.complete("- [ ] Deploy the thing ➕ 2026-09-01", "2026-09-19"))
            .isEqualTo("- [x] Deploy the thing ➕ 2026-09-01 ✅ 2026-09-19")
    }

    @Test
    fun `in progress is an open state`() {
        assertThat(TaskLine.isOpen("    - [/] halfway")).isTrue()
        assertThat(TaskLine.complete("    - [/] halfway", "2026-09-19"))
            .isEqualTo("    - [x] halfway ✅ 2026-09-19")
    }

    @Test
    fun `a done or cancelled line cannot be completed again`() {
        assertThat(TaskLine.complete("- [x] done ✅ 2026-09-10", "2026-09-19")).isNull()
        assertThat(TaskLine.complete("- [-] cancelled", "2026-09-19")).isNull()
        assertThat(TaskLine.complete("not a task at all", "2026-09-19")).isNull()
    }

    @Test
    fun `a repeating task is recognised so it can be left alone`() {
        assertThat(TaskLine.isRecurring("- [ ] water the plants 🔁 every week ⏳ 2026-09-19")).isTrue()
        assertThat(TaskLine.isRecurring("- [ ] water the plants ⏳ 2026-09-19")).isFalse()
    }

    @Test
    fun `reads back exactly what the index stored`() {
        val raw = "    - [ ] Move [[Clusters|the five clusters]] to **Argo** ➕ 2026-09-01 ⏳ 2026-09-04"
        // Parsed on its own the indentation would read as a continuation, so
        // the reference here is the same line without it -- which is exactly
        // what indexedText does before parsing.
        val indexed = TaskExtractor.extract(MarkdownParser.parseNote(raw.trimStart())).single()

        assertThat(TaskLine.indexedText(raw)).isEqualTo(indexed.text)
        assertThat(TaskLine.indexedText(raw)).isEqualTo("Move the five clusters to Argo")
    }

    @Test
    fun `a repeat comes back, and the one just done is kept below it`() {
        val out = TaskLine.completeRecurring("- [ ] water the plants 🔁 every week ⏳ 2026-09-19", "2026-09-20")

        assertThat(out)
            .containsExactly(
                "- [ ] water the plants 🔁 every week ⏳ 2026-09-26",
                "- [x] water the plants 🔁 every week ⏳ 2026-09-19 ✅ 2026-09-20",
            ).inOrder()
    }

    @Test
    fun `every date moves together, so the gaps between them survive`() {
        val out =
            TaskLine.completeRecurring(
                "- [ ] rent 🔁 every month 🛫 2026-09-15 ⏳ 2026-09-18 📅 2026-09-20",
                "2026-09-20",
            )

        // Due leads: 20 September to 20 October is 30 days, and start and
        // scheduled move by the same 30.
        assertThat(out!!.first())
            .isEqualTo("- [ ] rent 🔁 every month 🛫 2026-10-15 ⏳ 2026-10-18 📅 2026-10-20")
    }

    @Test
    fun `when done counts from the day it was ticked`() {
        val out =
            TaskLine.completeRecurring(
                "- [ ] weigh in 🔁 every week when done ⏳ 2026-09-10",
                "2026-09-20",
            )

        assertThat(out!!.first()).isEqualTo("- [ ] weigh in 🔁 every week when done ⏳ 2026-09-27")
    }

    @Test
    fun `a repeat left for weeks comes back in the future, not in the past`() {
        val out = TaskLine.completeRecurring("- [ ] bins 🔁 every day ⏳ 2026-09-10", "2026-09-20")

        assertThat(out!!.first()).isEqualTo("- [ ] bins 🔁 every day ⏳ 2026-09-21")
    }

    @Test
    fun `the created date is left where it was`() {
        val out = TaskLine.completeRecurring("- [ ] tidy 🔁 every week ➕ 2026-01-01 ⏳ 2026-09-19", "2026-09-20")

        assertThat(out!!.first()).contains("➕ 2026-01-01")
        assertThat(out.first()).contains("⏳ 2026-09-26")
    }

    @Test
    fun `a rule it will not act on is refused rather than guessed at`() {
        assertThat(TaskLine.completeRecurring("- [ ] standup 🔁 every weekday ⏳ 2026-09-19", "2026-09-20")).isNull()
        assertThat(TaskLine.completeRecurring("- [ ] x 🔁 every week on Sunday ⏳ 2026-09-19", "2026-09-20")).isNull()
    }

    @Test
    fun `a repeat with no date has no next occurrence to describe`() {
        assertThat(TaskLine.completeRecurring("- [ ] someday 🔁 every week", "2026-09-20")).isNull()
    }

    @Test
    fun `a task that does not repeat is not this function's business`() {
        assertThat(TaskLine.completeRecurring("- [ ] plain ⏳ 2026-09-19", "2026-09-20")).isNull()
        assertThat(TaskLine.completeRecurring("- [x] done 🔁 every week ⏳ 2026-09-19", "2026-09-20")).isNull()
    }

    @Test
    fun `indentation of a nested repeat is kept on both lines`() {
        val out = TaskLine.completeRecurring("    - [ ] child 🔁 every day ⏳ 2026-09-19", "2026-09-20")

        assertThat(out!!).hasSize(2)
        assertThat(out.all { it.startsWith("    - [") }).isTrue()
    }
}
