package me.parham1995.notes.data.git

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.ReadOnlyFailure
import me.parham1995.notes.sync.SyncBase
import me.parham1995.notes.sync.SyncPlan
import me.parham1995.notes.sync.VaultEntry
import me.parham1995.notes.sync.VaultSink
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * A read-only clone, against repositories on disk: the tests
 * `read-only-vault.md` in notes-linux's `docs/specs/` asks of every app, by
 * the names it gives them.
 *
 * Two clones of one bare remote. The first is a desk, which commits and
 * pushes with JGit directly; the second is the phone's, which the app's own
 * transport syncs.
 */
@RunWith(RobolectricTestRunner::class)
class ReadOnlyCloneTest {
    private lateinit var root: File
    private lateinit var remote: File
    private lateinit var first: File
    private lateinit var second: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("read-only").toFile()
        AndroidGitEnvironment.install(File(root, "config"))
        remote = File(root, "remote.git")
        first = File(root, "first")
        second = File(root, "second")

        Git
            .init()
            .setBare(true)
            .setDirectory(remote)
            .setInitialBranch(BRANCH)
            .call()
            .close()
        Git.init().setDirectory(first).setInitialBranch(BRANCH).call().use { git ->
            git
                .remoteAdd()
                .setName("origin")
                .setUri(URIish(uri(remote)))
                .call()
            File(first, NOTE).writeText("# Note\n\nfirst\n")
            File(first, OTHER).writeText("other\n")
            git.add().addFilepattern(".").call()
            commit(git, "seed")
            git
                .push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("$BRANCH:$BRANCH"))
                .call()
        }
        clone(remote, second)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** [repository]'s address as git writes one for a folder on this disk. */
    private fun uri(repository: File): String = "file://" + repository.absolutePath

    private fun clone(
        from: File,
        into: File,
    ) = Git
        .cloneRepository()
        .setURI(uri(from))
        .setDirectory(into)
        .setBranch(BRANCH)
        .call()
        .close()

    private fun commit(
        git: Git,
        message: String,
    ) = git
        .commit()
        .setMessage(message)
        .setAuthor("A Desk", "desk@example.com")
        .setCommitter("A Desk", "desk@example.com")
        .call()

    /** Changes [path] at the desk and pushes it to [to], and says which commit that was. */
    private fun pushFromFirst(
        path: String,
        text: String,
        to: String = "origin",
    ): String =
        Git.open(first).use { git ->
            File(first, path).writeText(text)
            git.add().addFilepattern(path).call()
            val made = commit(git, "change $path")
            git
                .push()
                .setRemote(to)
                .setRefSpecs(RefSpec("$BRANCH:$BRANCH"))
                .call()
            made.name
        }

    private fun transport(depth: Int = 0) =
        GitSshVaultSync(
            workTree = second,
            cloneUrl = uri(remote),
            branch = BRANCH,
            keys = SshKeyStore(ApplicationProvider.getApplicationContext()),
            vaultId = 1,
            configDir = File(root, "config"),
            shallowDepth = depth,
        )

    /** One sync as the app runs it: plan, and apply what the plan holds. */
    private suspend fun sync(
        base: String?,
        depth: Int = 0,
    ): SyncPlan {
        val transport = transport(depth)
        val plan = transport.plan(SyncBase(commit = base, manifest = emptyMap()))
        if (!plan.isEmpty) transport.apply(plan, NoSink)
        return plan
    }

    private object NoSink : VaultSink {
        override suspend fun write(
            path: String,
            bytes: ByteArray,
            sha: String,
        ) = Unit

        override suspend fun record(
            entry: VaultEntry,
            state: LocalState,
        ) = Unit

        override suspend fun move(
            from: String,
            to: String,
        ) = Unit

        override suspend fun delete(path: String) = Unit
    }

    private fun head(of: File): String = Git.open(of).use { it.repository.resolve(Constants.HEAD).name }

    private fun tip(of: File): String = Git.open(of).use { it.repository.resolve("refs/heads/$BRANCH").name }

    /** `git rev-list @{u}..HEAD`: the commits the clone has that its upstream does not. */
    private fun ahead(of: File): Int =
        Git.open(of).use { git ->
            val upstream = git.repository.resolve("refs/remotes/origin/$BRANCH")
            git
                .log()
                .add(git.repository.resolve(Constants.HEAD))
                .not(upstream)
                .call()
                .count()
        }

    /** `git status --porcelain`, as the paths it would list. */
    private fun changed(of: File): Set<String> =
        Git.open(of).use { git ->
            val status = git.status().call()
            status.modified + status.changed + status.added + status.removed + status.missing + status.untracked
        }

    private fun localConfig(of: File): File = File(of, ".git/config")

    @Test
    fun aCloneIsReadOnlyByItsOwnConfig() {
        // Not set is not read-only.
        assertThat(CloneConfig.isReadOnly(second)).isFalse()

        fun said(value: String): Boolean {
            val config = localConfig(second)
            val kept = config.readText()
            config.writeText("$kept[daftar]\n\treadonly$value\n")
            return CloneConfig.isReadOnly(second).also { config.writeText(kept) }
        }
        listOf("true", "yes", "on", "1").forEach { assertThat(said(" = $it")).isTrue() }
        listOf("false", "no", "off", "0").forEach { assertThat(said(" = $it")).isFalse() }
        // Not a git boolean: when in doubt the app does not write.
        assertThat(said(" = perhaps")).isTrue()
        // With no value at all the key is true, as git reads it.
        assertThat(said("")).isTrue()

        // The same key in the user's config is not read.
        val user = SystemReader.getInstance().userConfig
        user.setBoolean("daftar", null, "readonly", true)
        user.save()
        try {
            assertThat(CloneConfig.isReadOnly(second)).isFalse()
        } finally {
            user.unset("daftar", null, "readonly")
            user.save()
        }

        // A folder that is no repository is not read-only.
        assertThat(CloneConfig.isReadOnly(File(root, "plain").apply { mkdirs() })).isFalse()

        // The switch writes that one key in the clone's own config, and takes it off again.
        CloneConfig.setReadOnly(second, true)
        assertThat(CloneConfig.isReadOnly(second)).isTrue()
        assertThat(localConfig(second).readText()).contains("readonly = true")
        CloneConfig.setReadOnly(second, false)
        assertThat(CloneConfig.isReadOnly(second)).isFalse()
    }

    @Test
    fun aReadOnlySyncOnlyPulls() =
        runTest {
            val start = head(second)
            CloneConfig.setReadOnly(second, true)
            // Changed by hand in the read-only clone, by some other tool.
            File(second, OTHER).writeText("changed by hand\n")

            val pushed = pushFromFirst(NOTE, "# Note\n\nsecond\n")
            val plan = sync(base = start)

            // The commit reached the read-only clone.
            assertThat(plan.modifies.map { it.path }).containsExactly(NOTE)
            assertThat(File(second, NOTE).readText()).isEqualTo("# Note\n\nsecond\n")
            assertThat(head(second)).isEqualTo(pushed)
            // Nothing of its own was made, and nothing went up.
            assertThat(ahead(second)).isEqualTo(0)
            assertThat(tip(remote)).isEqualTo(pushed)
            // The hand change is not committed, and is still there.
            assertThat(File(second, OTHER).readText()).isEqualTo("changed by hand\n")
            assertThat(changed(second)).containsExactly(OTHER)

            // And again with nothing new: up to date, and still untouched.
            assertThat(sync(base = pushed).isEmpty).isTrue()
            assertThat(head(second)).isEqualTo(pushed)
            assertThat(changed(second)).containsExactly(OTHER)
        }

    @Test
    fun `a read-only clone refuses a write at the transport too`() =
        runTest {
            CloneConfig.setReadOnly(second, true)
            val before = head(second)

            val refused =
                runCatching {
                    transport().write(
                        NOTE,
                        "docs: change",
                        me.parham1995.notes.sync
                            .Author("A", "a@example.com"),
                    ) {
                        "changed\n"
                    }
                }.exceptionOrNull()

            assertThat(refused).isInstanceOf(ReadOnlyFailure.ReadOnly::class.java)
            assertThat(head(second)).isEqualTo(before)
            assertThat(tip(remote)).isEqualTo(before)
            assertThat(changed(second)).isEmpty()
        }

    @Test
    fun aReadOnlySyncDoesNotMendADivergedClone() =
        runTest {
            val start = head(second)
            // A commit of its own, made by another tool.
            val own =
                Git.open(second).use { git ->
                    File(second, OTHER).writeText("mine\n")
                    git.add().addFilepattern(OTHER).call()
                    commit(git, "a commit of the clone's own").name
                }
            CloneConfig.setReadOnly(second, true)
            File(second, NOTE).writeText("# Note\n\nhalf written\n")
            pushFromFirst(NOTE, "# Note\n\nsecond\n")

            val failure = runCatching { sync(base = start) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ReadOnlyFailure.ReadOnlyDiverged::class.java)
            // It names what is in the way.
            assertThat((failure as ReadOnlyFailure.ReadOnlyDiverged).files).contains(NOTE)
            // The commit, the branch and the working tree are as they were.
            assertThat(head(second)).isEqualTo(own)
            assertThat(tip(second)).isEqualTo(own)
            assertThat(File(second, OTHER).readText()).isEqualTo("mine\n")
            assertThat(File(second, NOTE).readText()).isEqualTo("# Note\n\nhalf written\n")
            assertThat(changed(second)).containsExactly(NOTE)
        }

    @Test
    fun `a hand change the pull would write over is diverged, and stays`() =
        runTest {
            val start = head(second)
            CloneConfig.setReadOnly(second, true)
            File(second, NOTE).writeText("# Note\n\nchanged by hand\n")
            pushFromFirst(NOTE, "# Note\n\nsecond\n")

            val failure = runCatching { sync(base = start) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ReadOnlyFailure.ReadOnlyDiverged::class.java)
            assertThat((failure as ReadOnlyFailure.ReadOnlyDiverged).files).containsExactly(NOTE)
            assertThat(head(second)).isEqualTo(start)
            assertThat(File(second, NOTE).readText()).isEqualTo("# Note\n\nchanged by hand\n")
        }

    /**
     * What the phone's clone really is: one commit deep. A fetch that deep
     * brings the new tip without the commits between, so the fast-forward
     * cannot be shown by walking history, and is known by the clone still
     * standing on the tip it last pulled.
     */
    @Test
    fun `a shallow read-only clone follows the tip it last pulled`() =
        runTest {
            val shallow = File(root, "shallow")
            Git
                .cloneRepository()
                .setURI(uri(remote))
                .setDirectory(shallow)
                .setBranch(BRANCH)
                .setDepth(1)
                .call()
                .close()
            second = shallow
            val start = head(second)
            CloneConfig.setReadOnly(second, true)
            File(second, OTHER).writeText("changed by hand\n")

            // Two commits, so the one between the clone and the new tip is never fetched.
            pushFromFirst(NOTE, "# Note\n\nsecond\n")
            val pushed = pushFromFirst(NOTE, "# Note\n\nthird\n")
            sync(base = start, depth = 1)

            assertThat(File(second, ".git/shallow").isFile).isTrue()
            assertThat(head(second)).isEqualTo(pushed)
            assertThat(File(second, NOTE).readText()).isEqualTo("# Note\n\nthird\n")
            assertThat(File(second, OTHER).readText()).isEqualTo("changed by hand\n")
            assertThat(tip(remote)).isEqualTo(pushed)
        }

    @Test
    fun theAddressIsTheUpstreamsRemote() =
        runTest {
            val start = head(second)
            // The address read is the remote's.
            assertThat(CloneConfig.address(second)).isEqualTo(uri(remote))

            // A second bare remote with the same history: a mirror.
            val mirror = File(root, "mirror.git")
            Git
                .cloneRepository()
                .setBare(true)
                .setURI(uri(remote))
                .setDirectory(mirror)
                .call()
                .close()
            Git.open(first).use {
                it
                    .remoteAdd()
                    .setName("mirror")
                    .setUri(URIish(uri(mirror)))
                    .call()
            }

            assertThat(CloneConfig.setAddress(second, "  ${uri(mirror)}\n")).isTrue()
            assertThat(CloneConfig.address(second)).isEqualTo(uri(mirror))
            assertThat(localConfig(second).readText()).contains("url = ${uri(mirror)}")

            // The next sync pulls a commit pushed there, and only there.
            val pushed = pushFromFirst(NOTE, "# Note\n\nfrom the mirror\n", to = "mirror")
            sync(base = start)
            assertThat(File(second, NOTE).readText()).isEqualTo("# Note\n\nfrom the mirror\n")
            assertThat(head(second)).isEqualTo(pushed)
            assertThat(tip(remote)).isEqualTo(start)

            // An empty or a blank address is refused and the old one stays.
            assertThat(CloneConfig.setAddress(second, "")).isFalse()
            assertThat(CloneConfig.setAddress(second, "   ")).isFalse()
            assertThat(CloneConfig.address(second)).isEqualTo(uri(mirror))

            // With the upstream on a remote named `backup`, that is the one read and written.
            Git.open(second).use { git ->
                git
                    .remoteAdd()
                    .setName("backup")
                    .setUri(URIish(uri(remote)))
                    .call()
                val config = git.repository.config
                config.setString("branch", BRANCH, "remote", "backup")
                config.save()
            }
            assertThat(CloneConfig.remoteName(second)).isEqualTo("backup")
            assertThat(CloneConfig.address(second)).isEqualTo(uri(remote))
            assertThat(CloneConfig.setAddress(second, "/somewhere/else.git")).isTrue()
            Git.open(second).use { git ->
                val config = git.repository.config
                assertThat(config.getString("remote", "backup", "url")).isEqualTo("/somewhere/else.git")
                assertThat(config.getString("remote", "origin", "url")).isEqualTo(uri(mirror))
            }
        }

    private companion object {
        const val BRANCH = "main"
        const val NOTE = "Note.md"
        const val OTHER = "Other.md"
    }
}
