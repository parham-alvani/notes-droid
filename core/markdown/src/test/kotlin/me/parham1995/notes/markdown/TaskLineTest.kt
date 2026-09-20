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
}
