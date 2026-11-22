package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * A task in the editor: `look.md` in notes-linux's `docs/specs/`, "Editor:
 * the task box" and "Editor: date chips". The tests named in camel case are
 * the ones every app has, with today given as 2026-11-22.
 */
class EditorTasksTest {
    private fun read(
        text: String,
        before: EditorState = EditorState(),
    ) = EditorMarkup.line(text, before).line

    private fun note(text: String): List<EditorLine> {
        val document = EditorDocument()
        document.update(text)
        return List(document.size) { document.line(it) }
    }

    private fun chips(text: String) = read(text).task?.chips.orEmpty()

    private fun EditorChip.said(text: String) = text.substring(start, end)

    private fun toggle(
        text: String,
        caret: Int = 0,
    ) = EditorCommands.toggleTask(text, at = text.lastIndexOf('\n') + 1, start = caret, end = caret, today = TODAY)!!

    /** The vault's own `r`, "In Review", which is under way. */
    private val vault =
        TaskStatuses.of(emptyList(), listOf(TaskStatus('r', "In Review", 'x', StatusType.IN_PROGRESS)))

    @Test
    fun aTaskBoxIsDrawnWhenTheMarkIsComplete() {
        assertThat(read("- [ ] ").task).isNotNull()
        assertThat(read("- [ ]").task).isNull()
        for (line in listOf("  * [x] a", "1. [/] a", "> - [ ] a", "- [r] a")) {
            assertThat(read(line).task).isNotNull()
        }
        for (line in listOf("- [] a", "- [xx] a", "-[ ] a", "[ ] a")) {
            assertThat(read(line).task).isNull()
        }
        val code = note("```\n- [ ] a\n```")
        assertThat(code[1].task).isNull()
    }

    @Test
    fun theBoxCoversTheMarkAndTheBrackets() {
        val bullet = read("- [ ] Buy").task!!
        assertThat(bullet.box).isEqualTo(EditorRange(0, 5))
        assertThat(read("1. [x] a").task!!.box).isEqualTo(EditorRange(3, 6))
        // The indent and a quote's marks are not the box's.
        assertThat(read("  * [x] a").task!!.box).isEqualTo(EditorRange(2, 7))
        assertThat(read("> - [ ] a").task!!.box).isEqualTo(EditorRange(2, 7))

        for (caret in listOf(0, 3, 5)) {
            assertThat(EditorTasks.boxDrawn(bullet, EditorRange(caret, caret))).isFalse()
        }
        assertThat(EditorTasks.boxDrawn(bullet, EditorRange(6, 6))).isTrue()
        // A selection that touches the box counts, and a caret on another line does not.
        assertThat(EditorTasks.boxDrawn(bullet, EditorRange(5, 9))).isFalse()
        assertThat(EditorTasks.boxDrawn(bullet, null)).isTrue()
    }

    @Test
    fun aClickOnTheBoxIsTheTaskToggle() {
        val open = "- [ ] Call ➕ 2026-11-20"
        val done = "- [x] Call ➕ 2026-11-20 ✅ 2026-11-22"
        assertThat(toggle(open).text).isEqualTo(done)
        assertThat(toggle(done).text).isEqualTo(open)
        assertThat(toggle("- [/] a").text).isEqualTo("- [x] a ✅ 2026-11-22")
        assertThat(toggle("- [-] a ❌ 2026-11-01").text).isEqualTo("- [ ] a")
        assertThat(toggle("- [/] a [reason:: waiting]").text).isEqualTo("- [x] a ✅ 2026-11-22")

        // Always what the task toggle gives.
        for (line in listOf(open, done, "- [/] a", "- [-] a ❌ 2026-11-01", "- [ ] Water 🔁 every week 📅 2026-11-20")) {
            assertThat(toggle(line).text).isEqualTo(TaskLine.toggled(line, TODAY)!!.joinToString("\n"))
        }
        // The caret keeps its offset in the text of the line.
        assertThat(toggle(open, caret = 8).start).isEqualTo(8)
        assertThat(toggle(done, caret = 8).start).isEqualTo(8)
    }

    @Test
    fun `a tap on a repeating task leaves the caret on the same text`() {
        val text = "before\n- [ ] Water 🔁 every week 📅 2026-11-20\nafter"
        val caret = text.indexOf("Water") + 2
        val after = text.indexOf("after") + 1
        val edit = EditorCommands.toggleTask(text, at = 7, start = caret, end = after, today = TODAY)!!
        val lines = edit.text.split('\n')
        assertThat(lines).hasSize(4)
        assertThat(lines[1]).isEqualTo("- [ ] Water 🔁 every week 📅 2026-11-27")
        assertThat(lines[2]).isEqualTo("- [x] Water 🔁 every week 📅 2026-11-20 ✅ 2026-11-22")
        // The caret was in "Water" of the task, which is the finished line now.
        assertThat(edit.start).isEqualTo(edit.text.lastIndexOf("Water") + 2)
        assertThat(edit.end).isEqualTo(edit.text.indexOf("after") + 1)
        // A place before the task does not move.
        assertThat(EditorCommands.toggleTask(text, at = 7, start = 3, end = 3, today = TODAY)!!.start).isEqualTo(3)
    }

    @Test
    fun `a task in a quote keeps its quote marks, and a line that is no task is not changed`() {
        assertThat(toggle("> - [ ] a").text).isEqualTo("> - [x] a ✅ 2026-11-22")
        assertThat(toggle("> - [ ] a 🔁 every day 📅 2026-11-22").text)
            .isEqualTo("> - [ ] a 🔁 every day 📅 2026-11-23\n> - [x] a 🔁 every day 📅 2026-11-22 ✅ 2026-11-22")
        assertThat(EditorCommands.toggleTask("- plain", 0, 0, 0, TODAY)).isNull()
    }

    @Test
    fun `a status from the sheet is the status change every other place makes`() {
        val edit = EditorCommands.setStatus("a\n- [ ] b", at = 2, start = 0, end = 0, status = '-', today = TODAY)!!
        assertThat(edit.text).isEqualTo("a\n- [-] b ❌ 2026-11-22")
        assertThat(EditorCommands.statusAt("a\n> - [/] b", 5)).isEqualTo('/')
        assertThat(EditorCommands.statusAt("a\n- b", 3)).isNull()
        // The status it has already: nothing to change.
        assertThat(EditorCommands.setStatus("- [-] b", 0, 0, 0, '-', TODAY)).isNull()
    }

    @Test
    fun eachStatusHasItsDrawing() {
        fun drawn(
            status: Char,
            statuses: TaskStatuses = TaskStatuses.DEFAULT,
        ) = EditorTasks.drawing(status, statuses).let { it.icon to it.tint }

        assertThat(drawn(' ')).isEqualTo("square" to TaskTint.LIST_MARKER)
        assertThat(drawn('x')).isEqualTo("square-check-big" to TaskTint.DONE)
        assertThat(drawn('X')).isEqualTo("square-check-big" to TaskTint.DONE)
        assertThat(drawn('/')).isEqualTo("square-dot" to TaskTint.ACCENT)
        assertThat(drawn('-')).isEqualTo("square-x" to TaskTint.DONE)
        assertThat(drawn('>')).isEqualTo("square-arrow-right" to TaskTint.BLUE)
        assertThat(drawn('r', vault)).isEqualTo("square-dot" to TaskTint.ACCENT)
        assertThat(drawn('~', vault)).isEqualTo("square-dashed" to TaskTint.LIST_MARKER)
        // Without the vault's settings, `r` is a symbol the vault does not have.
        assertThat(drawn('r')).isEqualTo("square-dashed" to TaskTint.LIST_MARKER)
    }

    @Test
    fun `a done and a cancelled task are struck, and what is struck leaves out the chips and the reason`() {
        assertThat(EditorTasks.isStruck('x')).isTrue()
        assertThat(EditorTasks.isStruck('X')).isTrue()
        assertThat(EditorTasks.isStruck('-')).isTrue()
        assertThat(EditorTasks.isStruck(' ')).isFalse()
        assertThat(EditorTasks.isStruck('/')).isFalse()
        assertThat(EditorTasks.isStruck('r', vault)).isFalse()

        val text = "- [-] Sell the bike [reason:: no buyer] ➕ 2026-11-01 ❌ 2026-11-20 ^id"
        val struck = read(text).task!!.text.map { text.substring(it.start, it.end) }
        assertThat(struck).containsExactly("Sell the bike", "^id").inOrder()
    }

    @Test
    fun datesOnATaskAreChips() {
        val text = "- [ ] Pay 📅 2026-11-20 ⏳ 2026-11-18 🔁 every week ➕ 2026-11-01"
        val chips = chips(text)
        assertThat(chips.map { it.kind })
            .containsExactly(
                EditorChipKind.DUE,
                EditorChipKind.SCHEDULED,
                EditorChipKind.REPEAT,
                EditorChipKind.CREATED,
            ).inOrder()
        assertThat(chips.map { it.said(text) })
            .containsExactly("📅 2026-11-20", "⏳ 2026-11-18", "🔁 every week", "➕ 2026-11-01")
            .inOrder()
        assertThat(chips[0].day).isEqualTo(LocalDate.of(2026, 11, 20))

        assertThat(chips("- [ ] a 📅 2026-02-30")).isEmpty()
        assertThat(read("Meeting 📅 2026-11-20").task).isNull()
    }

    @Test
    fun `a repeat's rule ends before a priority, a block id and the line's end`() {
        fun rule(text: String) = chips(text).single { it.kind == EditorChipKind.REPEAT }.said(text)

        assertThat(rule("- [ ] a 🔁 every 2 weeks on Monday")).isEqualTo("🔁 every 2 weeks on Monday")
        assertThat(rule("- [ ] a 🔁 every day ⏫ 📅 2026-11-20")).isEqualTo("🔁 every day")
        assertThat(rule("- [ ] a 🔁 every day ^abc-1")).isEqualTo("🔁 every day")
        // The emoji alone has no rule, and a start and the two closing dates are chips too.
        assertThat(chips("- [ ] a 🔁")).isEmpty()
        assertThat(chips("- [x] a 🛫 2026-11-01 ✅ 2026-11-02 ❌ 2026-11-03").map { it.kind })
            .containsExactly(EditorChipKind.START, EditorChipKind.DONE, EditorChipKind.CANCELLED)
    }

    @Test
    fun `a date needs its one space and its ten characters, and inline code holds no chip`() {
        assertThat(chips("- [ ] a 📅2026-11-20")).isEmpty()
        assertThat(chips("- [ ] a 📅  2026-11-20")).isEmpty()
        assertThat(chips("- [ ] a 📅 2026-11-201")).isEmpty()
        assertThat(chips("- [ ] a 📅 2026-1-20")).isEmpty()
        assertThat(chips("- [ ] a `📅 2026-11-20`")).isEmpty()
        assertThat(chips("- [ ] a `code` 📅 2026-11-20")).hasSize(1)
        // In a quote and in a callout a task has its chips.
        assertThat(chips("> - [ ] a 📅 2026-11-20")).hasSize(1)
    }

    @Test
    fun aLateDueDateIsRed() {
        fun late(
            text: String,
            statuses: TaskStatuses = TaskStatuses.DEFAULT,
        ): Boolean {
            val task = read(text).task!!
            return task.chips.any { EditorTasks.isLate(it, task.status, TODAY_DATE, statuses) }
        }
        assertThat(late("- [ ] a 📅 2026-11-21")).isTrue()
        assertThat(late("- [ ] a 📅 2026-11-22")).isFalse()
        assertThat(late("- [x] a 📅 2026-11-21 ✅ 2026-11-22")).isFalse()
        assertThat(late("- [-] a 📅 2026-11-21")).isFalse()
        assertThat(late("- [/] a 📅 2026-11-21")).isTrue()
        // Scheduled long ago is blue, never red.
        assertThat(late("- [ ] a ⏳ 2026-11-01")).isFalse()
        assertThat(late("- [r] a 📅 2026-11-21", vault)).isTrue()
    }

    @Test
    fun aChipIsRawWithTheCaretInIt() {
        val text = "- [ ] a 📅 2026-11-20"
        val chip = chips(text).single()

        fun drawn(caret: Int) = EditorTasks.chipDrawn(chip, EditorRange(caret, caret))
        assertThat(drawn(text.indexOf("📅"))).isFalse()
        assertThat(drawn(text.indexOf("11"))).isFalse()
        assertThat(drawn(text.length)).isFalse()
        assertThat(drawn(text.indexOf("a"))).isTrue()
        assertThat(EditorTasks.chipDrawn(chip, null)).isTrue()
    }

    @Test
    fun `the box's marks open no italic, and the text after it is read as ever`() {
        val text = "- [*] a *b* and **c**"
        val line = read(text)
        assertThat(line.task!!.status).isEqualTo('*')
        assertThat(line.spans.filter { it.style == EditorStyle.EMPHASIS }.map { text.substring(it.start, it.end) })
            .containsExactly("b")
    }

    private companion object {
        const val TODAY = "2026-11-22"
        val TODAY_DATE: LocalDate = LocalDate.parse(TODAY)
    }
}
