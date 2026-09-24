package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Moving a task to another day, as an edit that can be re-run.
 *
 * Every case goes through [VaultEdits.reschedule] -- the function the queue
 * actually replays -- rather than the line helper underneath it, so finding the
 * task and moving its date are tested together, the way they run.
 */
class RescheduleTest {
    private fun move(
        note: String,
        line: Int,
        date: String?,
    ): String? {
        val text = TaskLine.indexedText(note.lines()[line].trimStart())!!
        return VaultEdits.reschedule(line, text, date)(note)
    }

    @Test
    fun `with only a scheduled date, the scheduled date moves`() {
        val note = "## Home\n\n- [ ] call the bank ➕ 2026-09-01 ⏳ 2026-09-10\n"

        assertThat(move(note, 2, "2026-09-25"))
            .isEqualTo("## Home\n\n- [ ] call the bank ➕ 2026-09-01 ⏳ 2026-09-25\n")
    }

    @Test
    fun `with due and scheduled, due goes to the day and scheduled keeps its distance`() {
        // Filed by due, so due is what has to land on the chosen day or the
        // task stays in the group it was taken out of. Scheduled was three
        // days before due and still is; created is history and stays.
        val note = "- [ ] call the bank ➕ 2026-09-01 ⏳ 2026-09-07 📅 2026-09-10"

        assertThat(move(note, 0, "2026-09-25"))
            .isEqualTo("- [ ] call the bank ➕ 2026-09-01 ⏳ 2026-09-22 📅 2026-09-25")
    }

    @Test
    fun `with due and start, start moves by the same days`() {
        val note = "- [ ] submit the report 🛫 2026-09-05 📅 2026-09-10 ^rep"

        assertThat(move(note, 0, "2026-09-12"))
            .isEqualTo("- [ ] submit the report 🛫 2026-09-07 📅 2026-09-12 ^rep")
    }

    @Test
    fun `a start date alone files nothing, so a scheduled date is added`() {
        val note = "- [ ] plan the trip 🛫 2026-09-05"

        assertThat(move(note, 0, "2026-09-25")).isEqualTo("- [ ] plan the trip 🛫 2026-09-05 ⏳ 2026-09-25")
    }

    @Test
    fun `with only a due date, the due date moves`() {
        val note = "- [ ] file taxes ➕ 2026-09-01 📅 2026-09-10"

        assertThat(move(note, 0, "2026-09-25")).isEqualTo("- [ ] file taxes ➕ 2026-09-01 📅 2026-09-25")
    }

    @Test
    fun `with no date at all, a scheduled date is added after the rest`() {
        val note = "- [ ] water the plants ➕ 2026-09-01"

        assertThat(move(note, 0, "2026-09-25")).isEqualTo("- [ ] water the plants ➕ 2026-09-01 ⏳ 2026-09-25")
        assertThat(move("- [ ] no metadata", 0, "2026-09-25")).isEqualTo("- [ ] no metadata ⏳ 2026-09-25")
    }

    @Test
    fun `a repeating task keeps its rule where it was`() {
        val note = "- [ ] bins 🔁 every week ⏳ 2026-09-19 ➕ 2026-09-01"

        assertThat(move(note, 0, "2026-09-26")).isEqualTo("- [ ] bins 🔁 every week ⏳ 2026-09-26 ➕ 2026-09-01")
        // A repeat with nothing to move gains a date after the rule, not in it.
        assertThat(move("- [ ] stretch 🔁 every day", 0, "2026-09-26"))
            .isEqualTo("- [ ] stretch 🔁 every day ⏳ 2026-09-26")
    }

    @Test
    fun `a block id stays at the very end`() {
        val moved = "- [ ] review ⏳ 2026-09-10 ^abc-123"
        val added = "- [ ] review ➕ 2026-09-01 ^abc-123"

        assertThat(move(moved, 0, "2026-09-25")).isEqualTo("- [ ] review ⏳ 2026-09-25 ^abc-123")
        assertThat(move(added, 0, "2026-09-25")).isEqualTo("- [ ] review ➕ 2026-09-01 ⏳ 2026-09-25 ^abc-123")
    }

    @Test
    fun `persian text is left exactly as written`() {
        val note = "## خانه\n\n- [ ] خرید نان و [[شیر]] ⏳ 2026-09-10\n- [ ] تماس با مادر\n"

        assertThat(move(note, 2, "2026-09-25"))
            .isEqualTo("## خانه\n\n- [ ] خرید نان و [[شیر]] ⏳ 2026-09-25\n- [ ] تماس با مادر\n")
        assertThat(move(note, 3, "2026-09-25"))
            .isEqualTo("## خانه\n\n- [ ] خرید نان و [[شیر]] ⏳ 2026-09-10\n- [ ] تماس با مادر ⏳ 2026-09-25\n")
    }

    @Test
    fun `nested tasks keep their indentation`() {
        val note = "- [ ] parent\n    - [ ] child ⏳ 2026-09-10\n"

        assertThat(move(note, 1, "2026-09-25")).isEqualTo("- [ ] parent\n    - [ ] child ⏳ 2026-09-25\n")
    }

    @Test
    fun `running it again changes nothing`() {
        val note = "- [ ] a ⏳ 2026-09-10\n- [ ] b\n- [ ] c 📅 2026-09-10\n"
        listOf(0, 1, 2).forEach { line ->
            val once = move(note, line, "2026-09-25")!!
            val text = TaskLine.indexedText(note.lines()[line])!!

            assertThat(VaultEdits.reschedule(line, text, "2026-09-25")(once)).isNull()
        }
    }

    @Test
    fun `a line already on that date is not an edit`() {
        assertThat(move("- [ ] a ⏳ 2026-09-25", 0, "2026-09-25")).isNull()
    }

    @Test
    fun `of two tasks that read the same, the recorded line is the one that moves`() {
        val note = "## A\n\n- [ ] follow up\n\n## B\n\n- [ ] follow up\n"

        assertThat(move(note, 6, "2026-09-25"))
            .isEqualTo("## A\n\n- [ ] follow up\n\n## B\n\n- [ ] follow up ⏳ 2026-09-25\n")
    }

    @Test
    fun `a note that grew above the task still finds it`() {
        val edit = VaultEdits.reschedule(0, "call the bank", "2026-09-25")

        assertThat(edit("# New heading\n\n- [ ] call the bank ⏳ 2026-09-10"))
            .isEqualTo("# New heading\n\n- [ ] call the bank ⏳ 2026-09-25")
    }

    @Test
    fun `a task that is gone is refused, not reported as done`() {
        val edit = VaultEdits.reschedule(0, "call the bank", "2026-09-25")

        assertThrows(TaskNotFound::class.java) { edit("- [ ] something else") }
        assertThrows(TaskNotFound::class.java) { edit(null) }
    }

    @Test
    fun `undo puts back exactly what was there`() {
        val cases =
            listOf(
                "- [ ] a ➕ 2026-09-01 ⏳ 2026-09-10 ^id1",
                "- [ ] b 📅 2026-09-10",
                "- [ ] c ➕ 2026-09-01 ^id2",
                "- [ ] e ➕ 2026-09-01 🛫 2026-09-02 ⏳ 2026-09-07 📅 2026-09-10 ✅ 2026-09-03",
                "- [ ] د 🔁 every week",
            )
        cases.forEach { original ->
            val line = TaskLine.reschedule(original, "2026-09-25")!!
            val back =
                line.previous?.let { TaskLine.reschedule(line.line, it)?.line }
                    ?: TaskLine.unschedule(line.line)

            assertThat(back).isEqualTo(original)
        }
    }

    @Test
    fun `taking off a date that is not there is not an edit`() {
        assertThat(move("- [ ] a 📅 2026-09-10", 0, null)).isNull()
    }
}
