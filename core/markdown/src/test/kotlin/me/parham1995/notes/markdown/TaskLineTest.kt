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
    fun `a rule it will not act on is ticked with no next occurrence rather than guessed at`() {
        assertThat(TaskLine.completeRecurring("- [ ] standup 🔁 every weekday ⏳ 2026-09-19", "2026-09-20"))
            .containsExactly("- [x] standup 🔁 every weekday ⏳ 2026-09-19 ✅ 2026-09-20")
        assertThat(TaskLine.completeRecurring("- [ ] x 🔁 every jalali week ⏳ 2026-09-19 ^id", "2026-09-20"))
            .containsExactly("- [x] x 🔁 every jalali week ⏳ 2026-09-19 ✅ 2026-09-20 ^id")
    }

    @Test
    fun `a repeat with no date has no next occurrence to describe`() {
        assertThat(TaskLine.completeRecurring("- [ ] someday 🔁 every week", "2026-09-20"))
            .containsExactly("- [x] someday 🔁 every week ✅ 2026-09-20")
    }

    @Test
    fun `a task that does not repeat is not this function's business`() {
        assertThat(TaskLine.completeRecurring("- [ ] plain ⏳ 2026-09-19", "2026-09-20")).isNull()
        assertThat(TaskLine.completeRecurring("- [x] done 🔁 every week ⏳ 2026-09-19", "2026-09-20")).isNull()
    }

    @Test
    fun `a jalali repeat keeps the day of the jalali month`() {
        fun next(
            line: String,
            today: String,
        ): String? = TaskLine.completeRecurring(line, today)?.first()

        // Dey 10 to Bahman 10; every month would give 2027-01-31.
        assertThat(next("- [ ] Pay 🔁 every jalali month ⏳ 2026-12-31", "2026-12-31"))
            .isEqualTo("- [ ] Pay 🔁 every jalali month ⏳ 2027-01-30")
        // Shahrivar 31 to Mehr 30.
        assertThat(next("- [ ] Pay 🔁 every jalali month ⏳ 2026-09-22", "2026-09-22"))
            .isEqualTo("- [ ] Pay 🔁 every jalali month ⏳ 2026-10-22")
        // 1403-12-30 to 1404-12-29: 1404 is not leap.
        assertThat(next("- [ ] Pay 🔁 every jalali year ⏳ 2025-03-20", "2025-03-20"))
            .isEqualTo("- [ ] Pay 🔁 every jalali year ⏳ 2026-03-20")
        // Shahrivar 10, stepped past Mehr 10 and Aban 10 to Azar 10.
        assertThat(next("- [ ] Pay 🔁 every jalali month ⏳ 2026-09-01", "2026-11-03"))
            .isEqualTo("- [ ] Pay 🔁 every jalali month ⏳ 2026-12-01")
        // From today, Aban 10, to Azar 10.
        assertThat(next("- [ ] Pay 🔁 every jalali month when done ⏳ 2026-10-01", "2026-11-01"))
            .isEqualTo("- [ ] Pay 🔁 every jalali month when done ⏳ 2026-12-01")
        // 📅 leads, Dey 12 to Bahman 12, 30 days; ⏳ moves by the same 30; ➕ never moves.
        assertThat(next("- [ ] Pay 🔁 every jalali month ➕ 2026-12-01 ⏳ 2026-12-31 📅 2027-01-02", "2026-12-31"))
            .isEqualTo("- [ ] Pay 🔁 every jalali month ➕ 2026-12-01 ⏳ 2027-01-30 📅 2027-02-01")
        // Dey 10 1405 to Farvardin 10 1406.
        assertThat(next("- [ ] Pay 🔁 every 3 jalali months ⏳ 2026-12-31", "2026-12-31"))
            .isEqualTo("- [ ] Pay 🔁 every 3 jalali months ⏳ 2027-03-30")

        assertThat(TaskLine.completeRecurring("- [ ] Pay 🔁 every jalali month ⏳ 2026-12-31", "2026-12-31")!!.last())
            .isEqualTo("- [x] Pay 🔁 every jalali month ⏳ 2026-12-31 ✅ 2026-12-31")
    }

    @Test
    fun `a jalali repeat off the calendar is finished without a next one`() {
        assertThat(TaskLine.completeRecurring("- [ ] Far 🔁 every 9000 jalali years ⏳ 2026-12-31", "2026-12-31"))
            .containsExactly("- [x] Far 🔁 every 9000 jalali years ⏳ 2026-12-31 ✅ 2026-12-31")
        assertThat(TaskLine.withStatus("- [ ] Far 🔁 every 9000 jalali years ⏳ 2026-12-31", 'x', "2026-12-31"))
            .containsExactly("- [x] Far 🔁 every 9000 jalali years ⏳ 2026-12-31 ✅ 2026-12-31")
    }

    @Test
    fun `a jalali week or day is refused, so the task is finished with no next one`() {
        assertThat(TaskLine.completeRecurring("- [ ] Pay 🔁 every jalali week ⏳ 2026-12-31", "2026-12-31"))
            .containsExactly("- [x] Pay 🔁 every jalali week ⏳ 2026-12-31 ✅ 2026-12-31")
        assertThat(TaskLine.completeRecurring("- [ ] Pay 🔁 every jalali day ⏳ 2026-12-31", "2026-12-31"))
            .containsExactly("- [x] Pay 🔁 every jalali day ⏳ 2026-12-31 ✅ 2026-12-31")
    }

    @Test
    fun `indentation of a nested repeat is kept on both lines`() {
        val out = TaskLine.completeRecurring("    - [ ] child 🔁 every day ⏳ 2026-09-19", "2026-09-20")

        assertThat(out!!).hasSize(2)
        assertThat(out.all { it.startsWith("    - [") }).isTrue()
    }

    @Test
    fun `a new task is written from its fields`() {
        assertThat(TaskLine.written(TaskFields())).isNull()
        assertThat(TaskLine.written(TaskFields(text = "  \n "))).isNull()
        var fields = TaskFields(text = "  Call Sara\n about the trip ")
        assertThat(TaskLine.written(fields)).isEqualTo("- [ ] Call Sara about the trip")
        fields =
            fields.copy(priority = "⏫", created = "2026-11-11", scheduled = "2026-11-14", recurring = "every week")
        assertThat(TaskLine.written(fields))
            .isEqualTo("- [ ] Call Sara about the trip ⏫ 🔁 every week ➕ 2026-11-11 ⏳ 2026-11-14")
        // What it writes is a task the rest of the app reads as one.
        val line = TaskLine.written(fields)!!
        assertThat(TaskLine.isTask(line)).isTrue()
        assertThat(TaskLine.indexedText(line)).isEqualTo("Call Sara about the trip")
        assertThat(TaskLine.written(fields.copy(status = '/'))).startsWith("- [/] Call")
    }
}
