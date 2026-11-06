package me.parham1995.notes.data.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset

/**
 * A note's history from a repository on disk, as `git log --follow` lists
 * it: across a rename, newest first, and each version read from where the
 * note was then. Synthetic.
 */
class GitNoteHistoryTest {
    private lateinit var root: File
    private lateinit var git: Git
    private var day = 0L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("git-history").toFile()
        git =
            Git
                .init()
                .setDirectory(root)
                .setInitialBranch(BRANCH)
                .call()
    }

    @After
    fun tearDown() {
        git.close()
        root.deleteRecursively()
    }

    private fun commit(
        message: String,
        change: () -> Unit,
    ): String {
        change()
        git.add().addFilepattern(".").call()
        git
            .add()
            .setUpdate(true)
            .addFilepattern(".")
            .call()
        val person =
            PersonIdent("A Person", "person@example.com", Instant.ofEpochSecond(START + day++ * DAY), ZoneOffset.UTC)
        return git
            .commit()
            .setMessage(message)
            .setAuthor(person)
            .setCommitter(person)
            .call()
            .name
    }

    private fun write(
        path: String,
        text: String,
    ) = File(root, path).apply { parentFile?.mkdirs() }.writeText(text)

    @Test
    fun `the history follows a rename and reads each version from where it was`() =
        runTest {
            val added =
                commit(
                    "docs: add the plan",
                ) { write("Plan.md", "# Plan\n\nfirst draft, long enough to be known again\n") }
            commit("docs: something else") { write("Other.md", "unrelated\n") }
            val edited =
                commit("docs: second draft\n\nwith a body") {
                    write("Plan.md", "# Plan\n\nfirst draft, long enough to be known again\nand a second line\n")
                }
            val moved =
                commit("docs: file the plan") {
                    File(root, "Plan.md").renameTo(File(root, "Work/Plan.md".also { File(root, "Work").mkdirs() }))
                }
            val last =
                commit("docs: third draft") {
                    write(
                        "Work/Plan.md",
                        "# Plan\n\nfirst draft, long enough to be known again\nand a second line\nand a third\n",
                    )
                }

            val history = GitNoteHistory(root, BRANCH)
            val versions = history.history("Work/Plan.md")

            assertThat(versions.map { it.commit }).containsExactly(last, moved, edited, added).inOrder()
            assertThat(versions.map { it.path })
                .containsExactly("Work/Plan.md", "Work/Plan.md", "Plan.md", "Plan.md")
                .inOrder()
            assertThat(versions[2].subject).isEqualTo("docs: second draft")
            assertThat(versions[2].author).isEqualTo("A Person")
            assertThat(versions[0].date).isEqualTo(Instant.ofEpochSecond(START + 4 * DAY))

            assertThat(
                history.content(versions.last()),
            ).isEqualTo("# Plan\n\nfirst draft, long enough to be known again\n")
            assertThat(history.content(versions.first())).endsWith("and a third\n")
        }

    @Test
    fun `a note no commit has has no history, and a commit the clone lacks has no text`() =
        runTest {
            commit("docs: add") { write("A.md", "a\n") }
            val history = GitNoteHistory(root, BRANCH)

            assertThat(history.history("B.md")).isEmpty()
            val version = history.history("A.md").single()
            assertThat(history.content(version.copy(commit = "0".repeat(40)))).isNull()
            assertThat(history.content(version.copy(path = "B.md"))).isNull()
        }

    private companion object {
        const val BRANCH = "main"
        const val START = 1_780_000_000L
        const val DAY = 86_400L
    }
}
