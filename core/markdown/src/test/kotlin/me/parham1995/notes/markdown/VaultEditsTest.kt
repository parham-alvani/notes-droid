package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultEditsTest {
    @Test
    fun `replaces the anchored line and keeps its indentation`() {
        val note =
            """
            ## Daftar

            - [ ] parent
                - [ ] child ➕ 2026-09-01
            """.trimIndent()

        val out = VaultEdits.replaceLine("- [ ] child ➕ 2026-09-01", "- [x] child ➕ 2026-09-01 ✅ 2026-09-19")(note)

        assertThat(out).contains("    - [x] child ➕ 2026-09-01 ✅ 2026-09-19")
        assertThat(out).contains("- [ ] parent")
    }

    @Test
    fun `one line can become two, each keeping the indentation`() {
        val note = "## Daftar\n\n- [ ] parent\n    - [ ] bins 🔁 every day ⏳ 2026-09-19\n"

        val out =
            VaultEdits.replaceLineWith(
                "- [ ] bins 🔁 every day ⏳ 2026-09-19",
                listOf("- [ ] bins 🔁 every day ⏳ 2026-09-21", "- [x] bins 🔁 every day ⏳ 2026-09-19 ✅ 2026-09-20"),
            )(note)

        assertThat(out).isEqualTo(
            "## Daftar\n\n- [ ] parent\n" +
                "    - [ ] bins 🔁 every day ⏳ 2026-09-21\n" +
                "    - [x] bins 🔁 every day ⏳ 2026-09-19 ✅ 2026-09-20\n",
        )
    }

    @Test
    fun `a line that is already what the edit wanted is not applicable`() {
        val note = "- [x] done ✅ 2026-09-19"

        assertThat(VaultEdits.replaceLine("- [ ] done", "- [x] done ✅ 2026-09-19")(note)).isNull()
    }

    @Test
    fun `an anchor that is no longer there is not found, not done`() {
        assertThrows(TaskNotFound::class.java) {
            VaultEdits.replaceLine("- [ ] gone", "- [x] gone")("- [ ] something else")
        }
        assertThrows(TaskNotFound::class.java) { VaultEdits.replaceLine("- [ ] gone", "- [x] gone")(null) }
    }

    @Test
    fun `windows line endings are kept by every line edit`() {
        val note = "## Alpha\r\n\r\n- [ ] first 📅 2026-10-01\r\n- [ ] second\r\n"
        val today = "2026-10-02"

        assertThat(VaultEdits.replaceLine("- [ ] second", "- [x] second ✅ $today")(note))
            .isEqualTo("## Alpha\r\n\r\n- [ ] first 📅 2026-10-01\r\n- [x] second ✅ $today\r\n")
        assertThat(VaultEdits.setStatus(3, "second", 'x', today)(note))
            .isEqualTo("## Alpha\r\n\r\n- [ ] first 📅 2026-10-01\r\n- [x] second ✅ $today\r\n")
        assertThat(VaultEdits.setReason(3, "second", "waiting")(note))
            .isEqualTo("## Alpha\r\n\r\n- [ ] first 📅 2026-10-01\r\n- [ ] second [reason:: waiting]\r\n")
        assertThat(VaultEdits.reschedule(2, "first", "2026-10-05")(note))
            .isEqualTo("## Alpha\r\n\r\n- [ ] first 📅 2026-10-05\r\n- [ ] second\r\n")
        assertThat(VaultEdits.addUnder("Alpha", "- [ ] third")(note))
            .isEqualTo("## Alpha\r\n\r\n- [ ] first 📅 2026-10-01\r\n- [ ] second\r\n- [ ] third\r\n")
        assertThat(VaultEdits.addUnder("Beta", "- [ ] third")(note))
            .isEqualTo("## Alpha\r\n\r\n- [ ] first 📅 2026-10-01\r\n- [ ] second\r\n\r\n## Beta\r\n\r\n- [ ] third\r\n")
        assertThat(VaultEdits.append("10:00\n\na thought")(note))
            .isEqualTo("## Alpha\r\n\r\n- [ ] first 📅 2026-10-01\r\n- [ ] second\r\n\r\n10:00\r\n\r\na thought\r\n")
    }

    @Test
    fun `a capture already at the end of a windows note is not written twice`() {
        val note = "# Scratch\r\n\r\n10:00\r\n\r\na thought\r\n"

        assertThat(VaultEdits.append("10:00\n\na thought")(note)).isNull()
    }

    @Test
    fun `adds a task at the end of its section, not the top`() {
        val note =
            """
            ## Alpha

            - [ ] first
            - [ ] second

            ## Beta

            - [ ] other
            """.trimIndent()

        val out = VaultEdits.addUnder("Alpha", "- [ ] third")(note)

        assertThat(out).isEqualTo(
            """
            ## Alpha

            - [ ] first
            - [ ] second
            - [ ] third

            ## Beta

            - [ ] other
            """.trimIndent(),
        )
    }

    @Test
    fun `a missing section is created at the end of the file`() {
        val out = VaultEdits.addUnder("Gamma", "- [ ] new")("## Alpha\n\n- [ ] first\n")

        assertThat(out).isEqualTo("## Alpha\n\n- [ ] first\n\n## Gamma\n\n- [ ] new\n")
    }

    @Test
    fun `a hash inside a fence is not a heading`() {
        val note =
            """
            ## Alpha

            ```bash
            # install the agent
            echo hi
            ```

            - [ ] first
            """.trimIndent()

        val out = VaultEdits.addUnder("Alpha", "- [ ] second")(note)

        assertThat(out).endsWith("- [ ] first\n- [ ] second")
    }

    @Test
    fun `a task joins the list it is added to rather than starting a new one`() {
        val out = VaultEdits.addUnder("", "- [ ] third")("## Alpha\n\n- [ ] first\n- [ ] second\n")

        assertThat(out).isEqualTo("## Alpha\n\n- [ ] first\n- [ ] second\n- [ ] third\n")
    }

    @Test
    fun `a capture after prose still gets its blank line`() {
        val out = VaultEdits.append("- a thought")("# Scratch\n\nsome prose\n")

        assertThat(out).isEqualTo("# Scratch\n\nsome prose\n\n- a thought\n")
    }

    @Test
    fun `a duplicate task is not added twice`() {
        assertThat(VaultEdits.addUnder("Alpha", "- [ ] first")("## Alpha\n\n- [ ] first\n")).isNull()
    }

    @Test
    fun `appending leaves one blank line and one trailing newline`() {
        val out = VaultEdits.append("- a thought")("# Scratch\n\nolder\n\n\n")

        assertThat(out).isEqualTo("# Scratch\n\nolder\n\n- a thought\n")
    }

    @Test
    fun `appending to a file that is not there writes it with a heading`() {
        val out = VaultEdits.append("- a thought", heading = "Scratchpad")(null)

        assertThat(out).isEqualTo("# Scratchpad\n\n- a thought\n")
    }

    @Test
    fun `appending twice leaves one copy`() {
        // A push that landed but whose answer never arrived is sent again on
        // the next flush, against the file that already holds it.
        val edit = VaultEdits.append("09:14\n\na longer thought\nover two lines")
        val once = edit("# Scratch\n\nolder\n")

        assertThat(once).isNotNull()
        assertThat(edit(once)).isNull()
    }

    @Test
    fun `an empty capture is not applicable`() {
        assertThat(VaultEdits.append("   ")("# Scratch\n")).isNull()
    }

    @Test
    fun `a new note is written only where there is none`() {
        val create = VaultEdits.createFile("# New\n")
        assertThat(create(null)).isEqualTo("# New\n")
        assertThat(create("# New\n")).isNull()
        org.junit.Assert.assertThrows(NoteExists::class.java) { create("written at the desk\n") }
    }
}
