package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A task moved to another status column, as the edit the queue replays.
 *
 * Through [VaultEdits.setStatus], so finding the task and rewriting its line
 * are tested together, the way they run.
 */
class SetStatusTest {
    private val today = "2026-10-25"

    private fun set(
        note: String,
        line: Int,
        status: Char,
    ): String? {
        val text = TaskLine.indexedText(note.lines()[line].trimStart())!!
        return VaultEdits.setStatus(line, text, status, today)(note)
    }

    @Test
    fun `under way, and back to do, changes the status alone`() {
        assertThat(set("- [ ] Write 📅 2026-10-27", 0, '/')).isEqualTo("- [/] Write 📅 2026-10-27")
        assertThat(set("- [/] Write 📅 2026-10-27", 0, ' ')).isEqualTo("- [ ] Write 📅 2026-10-27")
    }

    @Test
    fun `done from open is the tick a tap makes`() {
        val note = "## Kit\n\n- [/] Write ➕ 2026-10-01\n"
        assertThat(set(note, 2, 'x')).isEqualTo("## Kit\n\n- [x] Write ➕ 2026-10-01 ✅ $today\n")
        assertThat(set(note, 2, 'x')).isEqualTo(
            "## Kit\n\n" + TaskLine.complete("- [/] Write ➕ 2026-10-01", today) + "\n",
        )
    }

    @Test
    fun `cancelled is dated today`() {
        assertThat(set("- [ ] Write", 0, '-')).isEqualTo("- [-] Write ❌ $today")
    }

    @Test
    fun `reopening takes the closing date off`() {
        assertThat(set("- [x] Write ✅ 2026-10-20", 0, ' ')).isEqualTo("- [ ] Write")
        assertThat(set("- [x] Write 📅 2026-10-19 ✅ 2026-10-20", 0, '/')).isEqualTo("- [/] Write 📅 2026-10-19")
        assertThat(set("- [-] Write ❌ 2026-10-20", 0, ' ')).isEqualTo("- [ ] Write")
    }

    @Test
    fun `from one closed state to the other, the dates swap`() {
        assertThat(set("- [x] Write ✅ 2026-10-20", 0, '-')).isEqualTo("- [-] Write ❌ $today")
        assertThat(set("- [-] Write ❌ 2026-10-20", 0, 'x')).isEqualTo("- [x] Write ✅ $today")
    }

    @Test
    fun `a block id stays the last word`() {
        assertThat(set("- [ ] Write 📅 2026-10-27 ^w1", 0, '-')).isEqualTo("- [-] Write 📅 2026-10-27 ❌ $today ^w1")
        assertThat(set("- [ ] Write 📅 2026-10-27 ^w1", 0, 'x')).isEqualTo("- [x] Write 📅 2026-10-27 ✅ $today ^w1")
        assertThat(set("- [x] Write ✅ 2026-10-20 ^w1", 0, ' ')).isEqualTo("- [ ] Write ^w1")
        assertThat(set("- [ ] Write ^w1", 0, '/')).isEqualTo("- [/] Write ^w1")
    }

    @Test
    fun `a repeat done leaves the next occurrence above it, and the id on the one finished`() {
        val note = "## Home\n- [ ] Water 🔁 every week 📅 2026-10-25 ^water\n- [ ] Other\n"

        assertThat(set(note, 1, 'x')).isEqualTo(
            "## Home\n" +
                "- [ ] Water 🔁 every week 📅 2026-11-01\n" +
                "- [x] Water 🔁 every week 📅 2026-10-25 ✅ $today ^water\n" +
                "- [ ] Other\n",
        )
    }

    @Test
    fun `a repeat done twice is done once`() {
        val note = "- [ ] Water 🔁 every week 📅 2026-10-25"
        val edit = VaultEdits.setStatus(0, "Water", 'x', today)
        val once = edit(note)!!

        // The next occurrence reads the same and sits on the same line, so a
        // replay -- a push whose answer was lost -- must not tick it too.
        assertThat(edit(once)).isNull()
    }

    @Test
    fun `a repeat on a rule the app does not work out is refused, not made a one-off`() {
        assertThrows(
            RepeatNotWorkedOut::class.java,
        ) { set("- [ ] Water 🔁 when the soil is dry 📅 2026-10-25", 0, 'x') }
    }

    @Test
    fun `the status it already has is no edit`() {
        assertThat(set("- [/] Write", 0, '/')).isNull()
        assertThat(set("- [X] Write ✅ 2026-10-20", 0, 'x')).isNull()
        assertThat(set("- [ ] Write", 0, ' ')).isNull()
    }

    @Test
    fun `the task is found again after the note grew above it`() {
        val edit = VaultEdits.setStatus(0, "Write", '/', today)
        assertThat(edit("# Added at the desk\n\n- [ ] Write")).isEqualTo("# Added at the desk\n\n- [/] Write")
    }

    @Test
    fun `a nested task keeps its indentation`() {
        assertThat(set("- [ ] Parent\n    - [ ] Child", 1, '-')).isEqualTo("- [ ] Parent\n    - [-] Child ❌ $today")
    }

    @Test
    fun `a task that is gone is refused, not reported as done`() {
        val edit = VaultEdits.setStatus(0, "Write", '/', today)
        assertThrows(TaskNotFound::class.java) { edit("- [ ] something else") }
        assertThrows(TaskNotFound::class.java) { edit(null) }
    }

    @Test
    fun `no date takes off every date the task is filed by`() {
        val note = "- [ ] Write ➕ 2026-10-01 🛫 2026-10-02 ⏳ 2026-10-20 📅 2026-10-27 ^w1"
        val edit = VaultEdits.reschedule(0, "Write", null, undated = true)

        assertThat(edit(note)).isEqualTo("- [ ] Write ➕ 2026-10-01 🛫 2026-10-02 ^w1")
        assertThat(edit("- [ ] Write ➕ 2026-10-01")).isNull()
        // Without the flag it is still the undo of a move: the scheduled date alone.
        assertThat(VaultEdits.reschedule(0, "Write", null)(note))
            .isEqualTo("- [ ] Write ➕ 2026-10-01 🛫 2026-10-02 📅 2026-10-27 ^w1")
    }
}
