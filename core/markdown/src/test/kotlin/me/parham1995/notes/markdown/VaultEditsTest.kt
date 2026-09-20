package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
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
    fun `a line that is already what the edit wanted is not applicable`() {
        val note = "- [x] done ✅ 2026-09-19"

        assertThat(VaultEdits.replaceLine("- [ ] done", "- [x] done ✅ 2026-09-19")(note)).isNull()
    }

    @Test
    fun `an anchor that is no longer there is not applicable`() {
        assertThat(VaultEdits.replaceLine("- [ ] gone", "- [x] gone")("- [ ] something else")).isNull()
        assertThat(VaultEdits.replaceLine("- [ ] gone", "- [x] gone")(null)).isNull()
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
    fun `an empty capture is not applicable`() {
        assertThat(VaultEdits.append("   ")("# Scratch\n")).isNull()
    }
}
