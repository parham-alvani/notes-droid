package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskSorterTest {
    @Test
    fun `done and cancelled tasks go first, open ones last, each in its old order`() {
        val note =
            """
            - [ ] one
            - [x] two
            - [ ] three
            - [-] four
            - [X] five
            """.trimIndent()

        assertThat(TaskSorter.sort(note)).isEqualTo(
            """
            - [x] two
            - [-] four
            - [X] five
            - [ ] one
            - [ ] three
            """.trimIndent(),
        )
    }

    @Test
    fun `sub-items and indented lines travel with their task`() {
        val note =
            "- [ ] open\n" +
                "    - [x] its done child\n" +
                "\tnote with a tab\n" +
                "- [x] closed\n" +
                "    - [ ] its open child"

        assertThat(TaskSorter.sort(note)).isEqualTo(
            "- [x] closed\n" +
                "    - [ ] its open child\n" +
                "- [ ] open\n" +
                "    - [x] its done child\n" +
                "\tnote with a tab",
        )
    }

    @Test
    fun `other lines keep their places and tasks fill the task slots`() {
        val note =
            """
            Intro
            - [ ] a
            Between
            - [x] b
            Outro
            """.trimIndent()

        assertThat(TaskSorter.sort(note)).isEqualTo(
            """
            Intro
            - [x] b
            Between
            - [ ] a
            Outro
            """.trimIndent(),
        )
    }

    @Test
    fun `each section is sorted on its own`() {
        val note =
            """
            # Top
            - [ ] a
            - [x] b
            ## Next
            - [ ] c
            - [x] d
            """.trimIndent()

        assertThat(TaskSorter.sort(note)).isEqualTo(
            """
            # Top
            - [x] b
            - [ ] a
            ## Next
            - [x] d
            - [ ] c
            """.trimIndent(),
        )
    }

    @Test
    fun `a heading does not let a task cross into another section`() {
        val note = "- [ ] early\n## Later\n- [x] late"

        assertThat(TaskSorter.sort(note)).isNull()
    }

    @Test
    fun `a note already in order is no change`() {
        assertThat(TaskSorter.sort("- [x] a\n- [-] b\n- [ ] c\n")).isNull()
        assertThat(TaskSorter.sort("")).isNull()
        assertThat(TaskSorter.sort("Just prose.\n\nNo tasks.")).isNull()
    }

    @Test
    fun `indented tasks, other statuses and other bullets are not root tasks`() {
        // `[/]` and `[>]` are outside the plugin's pattern, and so is a `*`
        // bullet; each is an ordinary line that stays where it is.
        val note =
            """
            - [ ] open
            - [/] in progress
            * [x] star
            - [x] done
            """.trimIndent()

        assertThat(TaskSorter.sort(note)).isEqualTo(
            """
            - [x] done
            - [/] in progress
            * [x] star
            - [ ] open
            """.trimIndent(),
        )
    }

    @Test
    fun `a heading needs its space, and runs of up to six hashes`() {
        // `#tag` is a paragraph and `####### seven` is not a heading, so both
        // leave the tasks around them in one section.
        val note = "- [ ] a\n#tag\n- [x] b\n####### seven\n- [x] c"

        assertThat(TaskSorter.sort(note)).isEqualTo("- [x] b\n#tag\n- [x] c\n####### seven\n- [ ] a")
    }

    @Test
    fun `blank lines are kept where they were and the trailing newline survives`() {
        val note = "## Tasks\n\n- [ ] a\n\n- [x] b\n"

        assertThat(TaskSorter.sort(note)).isEqualTo("## Tasks\n\n- [x] b\n\n- [ ] a\n")
    }

    @Test
    fun `windows line endings are kept`() {
        val note = "- [ ] a\r\n- [x] b\r\n"

        assertThat(TaskSorter.sort(note)).isEqualTo("- [x] b\r\n- [ ] a\r\n")
    }

    @Test
    fun `sorting twice is sorting once`() {
        val note = "# A\n- [ ] 1\n    - x\n- [x] 2\n\n# B\n- [-] 3\n- [ ] 4\n- [x] 5\n"
        val once = TaskSorter.sort(note)!!

        assertThat(TaskSorter.sort(once)).isNull()
    }

    @Test
    fun `the edit is the sort, and a note already sorted is a quiet success`() {
        assertThat(VaultEdits.sortTasks()("- [ ] a\n- [x] b")).isEqualTo("- [x] b\n- [ ] a")
        assertThat(VaultEdits.sortTasks()("- [x] b\n- [ ] a")).isNull()
        assertThat(VaultEdits.sortTasks()(null)).isNull()
    }
}
