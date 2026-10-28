package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A task's `[reason:: …]`: read out of the text, written before the emoji,
 * and taken off whenever the status it was given with changes.
 *
 * The cases the spec Daftar for Linux and the Mac share asks every app to
 * have, so the three agree on the same lines.
 */
class TaskReasonTest {
    private val today = "2026-10-28"

    private fun task(line: String): ParsedTask = TaskExtractor.extract(MarkdownParser.parseNote(line)).single()

    private fun set(
        line: String,
        status: Char,
        reason: String? = null,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): String? {
        val text = TaskLine.indexedText(line)!!
        return VaultEdits.setStatus(0, text, status, today, statuses, reason)(line)
    }

    @Test
    fun `a cancelled task's reason is read out of its text`() {
        val task = task("- [-] Deploy [reason:: no budget] ➕ 2025-05-17 ❌ 2025-11-16")
        assertThat(task.text).isEqualTo("Deploy")
        assertThat(task.reason).isEqualTo("no budget")
        assertThat(task.cancelled).isEqualTo("2025-11-16")
        assertThat(task.status).isEqualTo('-')
    }

    @Test
    fun `a wikilink inside the reason is part of it`() {
        val task = task("- [r] Ship [reason:: waiting on [[Amir]]] 📅 2026-11-02 ^imp")
        assertThat(task.text).isEqualTo("Ship")
        assertThat(task.reason).isEqualTo("waiting on [[Amir]]")
        assertThat(task.due).isEqualTo("2026-11-02")
        assertThat(task.status).isEqualTo('r')
    }

    @Test
    fun `a field in the middle leaves one space where it was`() {
        val task = task("- [ ] Call [reason:: busy] the bank")
        assertThat(task.text).isEqualTo("Call the bank")
        assertThat(task.reason).isEqualTo("busy")
        assertThat(TaskLine.indexedText("- [ ] Call [reason:: busy] the bank")).isEqualTo("Call the bank")
    }

    @Test
    fun `the key is any case, with space around the colons, and only the first field counts`() {
        assertThat(TaskReason.split("Call [Reason ::x] the [reason:: y] bank"))
            .isEqualTo(TaskReason.Split("Call the [reason:: y] bank", "x", 5..16))
        assertThat(TaskReason.split("Call [reason:: ] the bank").reason).isNull()
        assertThat(TaskReason.split("Call [reason:: never closes").reason).isNull()
    }

    @Test
    fun `a plain bullet keeps the field as text`() {
        val item =
            MarkdownParser
                .parseNote("- Call [reason:: busy] the bank")
                .blocks
                .filterIsInstance<MdBlock.ListBlock>()
                .single()
                .items
                .single()
        assertThat(item.reason).isNull()
    }

    @Test
    fun `a reason is written before the emoji, its brackets turned round when they do not balance`() {
        assertThat(TaskLine.withReason("- [ ] T 📅 2026-11-02", "a ] b"))
            .isEqualTo("- [ ] T [reason:: a ) b] 📅 2026-11-02")
        assertThat(TaskLine.withReason("- [ ] T [reason:: a ) b] 📅 2026-11-02", "  "))
            .isEqualTo("- [ ] T 📅 2026-11-02")
        assertThat(TaskLine.withReason("- [ ] T 📅 2026-11-02 ^t1", "waits on\n[[Amir]]"))
            .isEqualTo("- [ ] T [reason:: waits on [[Amir]]] 📅 2026-11-02 ^t1")
        assertThat(TaskLine.withReason("- [ ] T [reason:: x] ^t1", "y")).isEqualTo("- [ ] T [reason:: y] ^t1")
        assertThat(TaskLine.withReason("- [ ] T [reason:: x]", "x")).isNull()
    }

    @Test
    fun `cancelling with a reason writes both, the dates kept`() {
        assertThat(set("- [ ] Write ⏳ 2026-10-04", '-', "no time"))
            .isEqualTo("- [-] Write [reason:: no time] ⏳ 2026-10-04 ❌ 2026-10-28")
    }

    @Test
    fun `ticking a cancelled task opens it without its reason or its date`() {
        assertThat(set("- [-] Write [reason:: no time] ⏳ 2026-10-04 ❌ 2026-10-28", ' '))
            .isEqualTo("- [ ] Write ⏳ 2026-10-04")
    }

    @Test
    fun `the same status with a new reason changes the reason alone`() {
        assertThat(set("- [/] Write [reason:: old] ⏳ 2026-10-04", '/', "new"))
            .isEqualTo("- [/] Write [reason:: new] ⏳ 2026-10-04")
        assertThat(set("- [/] Write [reason:: old] ⏳ 2026-10-04", '/')).isNull()
    }

    @Test
    fun `neither line of a ticked repeat keeps the reason`() {
        val lines =
            TaskLine.completeRecurring("- [/] Water [reason:: waiting on rain] 🔁 every day ⏳ 2026-10-28", today)!!
        assertThat(lines)
            .containsExactly(
                "- [ ] Water 🔁 every day ⏳ 2026-10-29",
                "- [x] Water 🔁 every day ⏳ 2026-10-28 ✅ 2026-10-28",
            ).inOrder()
        assertThat(TaskLine.complete("- [r] Ship [reason:: review] ^s", today, statuses))
            .isEqualTo("- [x] Ship ✅ 2026-10-28 ^s")
    }

    @Test
    fun `a custom status follows its type, and the reason goes with the old status`() {
        assertThat(set("- [ ] Ship 📅 2026-11-02", 'r', "waiting on Amir", statuses))
            .isEqualTo("- [r] Ship [reason:: waiting on Amir] 📅 2026-11-02")
        assertThat(set("- [r] Ship [reason:: waiting on Amir] 📅 2026-11-02", 'x', statuses = statuses))
            .isEqualTo("- [x] Ship 📅 2026-11-02 ✅ 2026-10-28")
        // Done to done keeps the day it was done.
        assertThat(set("- [x] Ship ✅ 2026-10-20", 'X', statuses = statuses)).isEqualTo("- [X] Ship ✅ 2026-10-20")
    }

    private val statuses =
        TaskStatuses.of(
            core = emptyList(),
            custom = listOf(TaskStatus('r', "In Review", 'x', StatusType.IN_PROGRESS)),
        )
}
