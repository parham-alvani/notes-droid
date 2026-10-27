package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every fixture here is made up. The repository is public; the vault is not. */
class HiddenFilesTest {
    private val agents = HiddenFiles(names = HiddenFiles.AGENT_FILES)

    @Test
    fun `the excluded files are read from Obsidian's app settings`() {
        val text =
            """
            {
              "alwaysUpdateLinks": true,
              "userIgnoreFilters": ["uploads/", "node_modules/", "", 7],
              "attachmentFolderPath": "uploads"
            }
            """.trimIndent()

        assertThat(HiddenFiles.ignoreFilters(text)).containsExactly("uploads/", "node_modules/").inOrder()
    }

    @Test
    fun `app settings that never set the filters hide nothing`() {
        assertThat(HiddenFiles.ignoreFilters("""{"alwaysUpdateLinks": true}""")).isEmpty()
        assertThat(HiddenFiles.ignoreFilters("""{"userIgnoreFilters": "uploads/"}""")).isEmpty()
        assertThat(HiddenFiles.ignoreFilters("[]")).isEmpty()
    }

    @Test
    fun `a folder filter hides the folder and everything under it`() {
        val hidden = HiddenFiles(ignoreFilters = listOf("uploads/"))

        assertThat(hidden.hides("uploads")).isTrue()
        assertThat(hidden.hides("uploads/diagram.png")).isTrue()
        assertThat(hidden.hides("uploads/2026/scan.pdf")).isTrue()
        assertThat(hidden.hides("uploads/Notes about uploads.md")).isTrue()
    }

    @Test
    fun `a folder filter does not reach a folder that only starts the same`() {
        val hidden = HiddenFiles(ignoreFilters = listOf("uploads/"))

        assertThat(hidden.hides("uploads2/diagram.png")).isFalse()
        assertThat(hidden.hides("uploads.md")).isFalse()
        assertThat(hidden.hides("Projects/uploads/diagram.png")).isFalse()
        assertThat(hidden.hides("Garden.md")).isFalse()
    }

    @Test
    fun `a filter written without its slash, or with a leading one, is the same folder`() {
        listOf("uploads", "/uploads/", "./uploads").forEach { filter ->
            val hidden = HiddenFiles(ignoreFilters = listOf(filter))
            assertThat(hidden.hides("uploads/diagram.png")).isTrue()
            assertThat(hidden.hides("Garden.md")).isFalse()
        }
    }

    @Test
    fun `a nested folder and a single file can be filtered`() {
        val hidden = HiddenFiles(ignoreFilters = listOf("Projects/Archive/", "Inbox/Scratch.md"))

        assertThat(hidden.hides("Projects/Archive/Old plan.md")).isTrue()
        assertThat(hidden.hides("Projects/Plan.md")).isFalse()
        assertThat(hidden.hides("Inbox/Scratch.md")).isTrue()
        assertThat(hidden.hides("Inbox/Scratchpad.md")).isFalse()
    }

    @Test
    fun `a regular expression is matched anywhere in the path`() {
        val hidden = HiddenFiles(ignoreFilters = listOf("""/\.excalidraw\.md$/"""))

        assertThat(hidden.hides("Drawings/Sketch.excalidraw.md")).isTrue()
        assertThat(hidden.hides("Drawings/Sketch.md")).isFalse()
    }

    @Test
    fun `a regular expression that does not compile is left out, not fatal`() {
        val hidden = HiddenFiles(ignoreFilters = listOf("/(unclosed/", "uploads/"))

        assertThat(hidden.hides("(unclosed/a.md")).isFalse()
        assertThat(hidden.hides("uploads/a.png")).isTrue()
    }

    @Test
    fun `an agent's instructions are hidden in every folder`() {
        assertThat(agents.hides("CLAUDE.md")).isTrue()
        assertThat(agents.hides("Learning/CLAUDE.md")).isTrue()
        assertThat(agents.hides("Projects/Garden/Beds/AGENTS.md")).isTrue()
        assertThat(agents.hides("Journal/GEMINI.md")).isTrue()
        assertThat(agents.hides("CLAUDE.local.md")).isTrue()
    }

    @Test
    fun `a name only like an agent's is a note someone wrote`() {
        assertThat(agents.hides("MyCLAUDE.md")).isFalse()
        assertThat(agents.hides("Learning/MyCLAUDE.md")).isFalse()
        assertThat(agents.hides("CLAUDE.md.bak")).isFalse()
        assertThat(agents.hides("claude.md")).isFalse()
        assertThat(agents.hides("CLAUDE.md/notes.md")).isFalse()
        assertThat(agents.hides("About CLAUDE.md")).isFalse()
    }

    @Test
    fun `nothing is hidden when nothing is asked for`() {
        assertThat(HiddenFiles.NONE.isEmpty).isTrue()
        assertThat(HiddenFiles.NONE.hides("CLAUDE.md")).isFalse()
        assertThat(HiddenFiles.NONE.hides("uploads/diagram.png")).isFalse()
        assertThat(HiddenFiles.NONE.hides("")).isFalse()
    }
}
