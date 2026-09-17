package me.parham1995.notes.data.git

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrepareCloneTargetTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a vault left by a REST sync is cleared so the clone can proceed`() {
        // Without this, switching from REST to SSH fails immediately: JGit
        // refuses to clone into a directory that is not empty.
        val target = temp.newFolder("vault")
        File(target, "Note.md").writeText("content")
        File(target, "Folder").mkdirs()
        File(target, "Folder/Nested.md").writeText("more")

        prepareCloneTarget(target)

        assertThat(target.isDirectory).isTrue()
        assertThat(target.listFiles()).isEmpty()
    }

    @Test
    fun `an existing clone is left alone`() {
        // Clearing here would throw away the repository on every sync.
        val target = temp.newFolder("vault")
        File(target, ".git").mkdirs()
        File(target, ".git/HEAD").writeText("ref: refs/heads/main")
        File(target, "Note.md").writeText("content")

        prepareCloneTarget(target)

        assertThat(File(target, ".git/HEAD").exists()).isTrue()
        assertThat(File(target, "Note.md").exists()).isTrue()
    }

    @Test
    fun `a missing directory is created`() {
        val target = File(temp.root, "not-there-yet")

        prepareCloneTarget(target)

        assertThat(target.isDirectory).isTrue()
    }
}
