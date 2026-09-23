package me.parham1995.notes.data.git

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.markdown.VaultEdits
import me.parham1995.notes.sync.Author
import me.parham1995.notes.sync.SyncBase
import me.parham1995.notes.sync.WriteOutcome
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Writing over git, against a repository on disk rather than over SSH.
 *
 * The one thing this exists to pin down cost a device test to find: for an SSH
 * vault the working tree *is* the app's file store, and an edit is written
 * there optimistically before it is sent. Reading the file back to compute the
 * edit therefore read the app's own scribble, decided the change was already
 * made, and dropped it from the queue without it ever reaching the repository.
 * The content has to come from the commit.
 */
@RunWith(RobolectricTestRunner::class)
class GitSshWriteTest {
    private lateinit var root: File
    private lateinit var origin: File
    private lateinit var workTree: File
    private val author = Author("A Person", "person@example.com")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("git-write").toFile()
        origin = File(root, "origin").apply { mkdirs() }
        workTree = File(root, "work")

        Git.init().setDirectory(origin).setInitialBranch(BRANCH).call().use { git ->
            File(origin, NOTE).apply { parentFile?.mkdirs() }.writeText("## Alpha\n\n- [ ] first\n")
            git.add().addFilepattern(NOTE).call()
            git
                .commit()
                .setMessage("seed")
                .setAuthor("Seed", "seed@example.com")
                .call()
        }
        Git
            .cloneRepository()
            .setURI(origin.toURI().toString())
            .setDirectory(workTree)
            .setBranch(BRANCH)
            .call()
            .close()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writer(vaultId: Long = 1) =
        GitSshVaultSync(
            workTree = workTree,
            remoteUrl = origin.toURI().toString(),
            branch = BRANCH,
            keys = SshKeyStore(ApplicationProvider.getApplicationContext()),
            vaultId = vaultId,
            configDir = File(root, "config"),
            // A local transport cannot serve a shallow fetch, and depth is
            // about the size of a clone over the network, not about this.
            shallowDepth = 0,
        )

    /** What the repository holds at the tip of [BRANCH], not what is checked out. */
    private fun originText(path: String): String? =
        Git.open(origin).use { git ->
            val head = git.repository.resolve("refs/heads/$BRANCH") ?: return null
            RevWalk(git.repository).use { walk ->
                val tree = walk.parseCommit(head).tree
                TreeWalk.forPath(git.repository, path, tree)?.use { found ->
                    String(git.repository.open(found.getObjectId(0)).bytes)
                }
            }
        }

    @Test
    fun `an edit is computed from the commit, not from the working tree`() =
        runTest {
            // Exactly what the app does before it sends: the new line is
            // already on disk, untracked, when the writer runs.
            File(workTree, SCRATCH).writeText("## 2026-09-20\n\n- 05:47 a thought\n")

            val outcome =
                writer().write(SCRATCH, "docs(scratchpad): capture a note", author) {
                    VaultEdits.addUnder("2026-09-20", "- 05:47 a thought")(it)
                }

            assertThat(outcome).isInstanceOf(WriteOutcome.Written::class.java)
            assertThat(originText(SCRATCH)).isEqualTo("## 2026-09-20\n\n- 05:47 a thought\n")
        }

    @Test
    fun `an edit the commit already carries is not applicable`() =
        runTest {
            val outcome =
                writer().write(NOTE, "docs(tasks): add", author) {
                    VaultEdits.addUnder("Alpha", "- [ ] first")(it)
                }

            assertThat(outcome).isEqualTo(WriteOutcome.NotApplicable)
        }

    @Test
    fun `a task is ticked in the file the repository holds`() =
        runTest {
            val outcome =
                writer().write(NOTE, "docs(tasks): complete", author) {
                    VaultEdits.replaceLine("- [ ] first", "- [x] first ✅ 2026-09-20")(it)
                }

            assertThat(outcome).isInstanceOf(WriteOutcome.Written::class.java)
            assertThat(originText(NOTE)).isEqualTo("## Alpha\n\n- [x] first ✅ 2026-09-20\n")
        }

    @Test
    fun `each vault's connections use its own key, whatever was built after it`() {
        val first = writer(vaultId = 1)
        // Building a second transport -- testing another vault's key in
        // Settings, say -- used to replace the process-wide factory.
        val second = writer(vaultId = 2)

        Git.open(workTree).use { git ->
            Transport.open(git.repository, URIish("ssh://git@github.com/owner/repo.git")).use { transport ->
                first.sshConfig.configure(transport)

                assertThat((transport as SshTransport).sshSessionFactory).isSameInstanceAs(first.sessions)
                assertThat(transport.sshSessionFactory).isNotSameInstanceAs(second.sessions)
            }
        }
    }

    @Test
    fun `a base commit this clone never had plans from the whole tree`() =
        runTest {
            // A vault switched from REST: the commit it recorded was never
            // fetched into this shallow clone. `resolve` hands back an id for
            // any full sha, so this used to reach parseCommit and throw
            // MissingObjectException on every sync.
            val base =
                SyncBase(
                    commit = "0123456789abcdef0123456789abcdef01234567",
                    manifest = mapOf(NOTE to "sha-from-the-rest-era"),
                )

            val plan = writer().plan(base)

            assertThat(plan.modifies.map { it.path }).containsExactly(NOTE)
            assertThat(plan.adds).isEmpty()
            assertThat(plan.deletes).isEmpty()
        }

    private companion object {
        const val BRANCH = "main"
        const val NOTE = "Tasks/Work.md"
        const val SCRATCH = "Scratchpad.md"
    }
}
