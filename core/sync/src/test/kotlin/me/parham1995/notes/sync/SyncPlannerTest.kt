package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncPlannerTest {
    private val filter = VaultFilter()

    private fun md(
        path: String,
        sha: String,
        size: Long = 100,
    ) = VaultEntry(path, sha, size, BlobKind.MARKDOWN)

    // -- fromTree ---------------------------------------------------------

    @Test
    fun `first sync downloads everything`() {
        val base = SyncBase(commit = null, manifest = emptyMap())
        val remote = listOf(md("a.md", "sha-a"), md("alpha/b.md", "sha-b"))

        val plan = SyncPlanner.fromTree(base, "head1", remote)

        assertThat(plan.adds.map { it.path }).containsExactly("a.md", "alpha/b.md")
        assertThat(plan.modifies).isEmpty()
        assertThat(plan.deletes).isEmpty()
        assertThat(plan.baseCommit).isNull()
        assertThat(plan.downloadBytes).isEqualTo(200)
    }

    @Test
    fun `unchanged files are counted, not downloaded`() {
        val base = SyncBase("old", mapOf("a.md" to "sha-a", "b.md" to "sha-b"))
        val remote = listOf(md("a.md", "sha-a"), md("b.md", "sha-b-new"))

        val plan = SyncPlanner.fromTree(base, "head2", remote)

        assertThat(plan.unchanged).isEqualTo(1)
        assertThat(plan.modifies.map { it.path }).containsExactly("b.md")
        assertThat(plan.adds).isEmpty()
        assertThat(plan.isEmpty).isFalse()
    }

    @Test
    fun `a file gone from the remote is deleted locally`() {
        val base = SyncBase("old", mapOf("a.md" to "sha-a", "gone.md" to "sha-gone"))

        val plan = SyncPlanner.fromTree(base, "head2", listOf(md("a.md", "sha-a")))

        assertThat(plan.deletes).containsExactly("gone.md")
        assertThat(plan.unchanged).isEqualTo(1)
    }

    @Test
    fun `same content at a new path is a rename, not a download`() {
        val base = SyncBase("old", mapOf("alpha/note.md" to "sha-x"))

        val plan = SyncPlanner.fromTree(base, "head2", listOf(md("beta/note.md", "sha-x")))

        assertThat(plan.renames).containsExactly(Rename("alpha/note.md", "beta/note.md", "sha-x"))
        // The point of detecting it: nothing crosses the network, and the old
        // path is not deleted-then-refetched.
        assertThat(plan.adds).isEmpty()
        assertThat(plan.deletes).isEmpty()
        assertThat(plan.downloadBytes).isEqualTo(0)
    }

    @Test
    fun `a rename that also changes content is a delete plus an add`() {
        val base = SyncBase("old", mapOf("alpha/note.md" to "sha-old"))

        val plan = SyncPlanner.fromTree(base, "head2", listOf(md("beta/note.md", "sha-new")))

        assertThat(plan.renames).isEmpty()
        assertThat(plan.adds.map { it.path }).containsExactly("beta/note.md")
        assertThat(plan.deletes).containsExactly("alpha/note.md")
    }

    @Test
    fun `identical content at several new paths pairs each removal only once`() {
        // Two files shared one blob; both moved. Each rename must consume a
        // distinct source, or one source gets reused and the other is dropped.
        val base = SyncBase("old", mapOf("a1.md" to "dup", "a2.md" to "dup"))
        val remote = listOf(md("b1.md", "dup"), md("b2.md", "dup"))

        val plan = SyncPlanner.fromTree(base, "head2", remote)

        assertThat(plan.renames).hasSize(2)
        assertThat(plan.renames.map { it.from }).containsExactly("a1.md", "a2.md")
        assertThat(plan.renames.map { it.to }).containsExactly("b1.md", "b2.md")
        assertThat(plan.deletes).isEmpty()
        assertThat(plan.adds).isEmpty()
    }

    // -- fromCompare ------------------------------------------------------

    @Test
    fun `compare maps each status to the right action`() {
        val base = SyncBase("old", mapOf("mod.md" to "sha-1", "del.md" to "sha-2"))
        val changes =
            listOf(
                CompareChange("new.md", ChangeStatus.ADDED, "sha-new"),
                CompareChange("mod.md", ChangeStatus.MODIFIED, "sha-1b"),
                CompareChange("del.md", ChangeStatus.REMOVED, null),
            )

        val plan = SyncPlanner.fromCompare(base, "head2", changes, filter)

        assertThat(plan.adds.map { it.path }).containsExactly("new.md")
        assertThat(plan.modifies.map { it.path }).containsExactly("mod.md")
        assertThat(plan.deletes).containsExactly("del.md")
    }

    @Test
    fun `compare rename with unchanged content moves on disk`() {
        val base = SyncBase("old", mapOf("alpha/note.md" to "sha-x"))
        val changes =
            listOf(
                CompareChange("beta/note.md", ChangeStatus.RENAMED, "sha-x", previousPath = "alpha/note.md"),
            )

        val plan = SyncPlanner.fromCompare(base, "head2", changes, filter)

        assertThat(plan.renames).containsExactly(Rename("alpha/note.md", "beta/note.md", "sha-x"))
        assertThat(plan.downloads).isEmpty()
    }

    @Test
    fun `compare rename with changed content re-downloads`() {
        val base = SyncBase("old", mapOf("alpha/note.md" to "sha-old"))
        val changes =
            listOf(
                CompareChange("beta/note.md", ChangeStatus.RENAMED, "sha-new", previousPath = "alpha/note.md"),
            )

        val plan = SyncPlanner.fromCompare(base, "head2", changes, filter)

        assertThat(plan.renames).isEmpty()
        assertThat(plan.deletes).containsExactly("alpha/note.md")
        assertThat(plan.adds.map { it.path }).containsExactly("beta/note.md")
    }

    @Test
    fun `a note renamed out of the vault is deleted, not followed`() {
        // Moving a note into a dot-directory takes it out of the vault. The old
        // path must still go, or it lingers on the device forever.
        val base = SyncBase("old", mapOf("alpha/note.md" to "sha-x"))
        val changes =
            listOf(
                CompareChange(".archive/note.md", ChangeStatus.RENAMED, "sha-x", previousPath = "alpha/note.md"),
            )

        val plan = SyncPlanner.fromCompare(base, "head2", changes, filter)

        assertThat(plan.deletes).containsExactly("alpha/note.md")
        assertThat(plan.adds).isEmpty()
        assertThat(plan.renames).isEmpty()
    }

    @Test
    fun `non-vault changes are ignored entirely`() {
        val base = SyncBase("old", mapOf("a.md" to "sha-a"))
        val changes =
            listOf(
                CompareChange(".github/workflows/ci.yaml", ChangeStatus.MODIFIED, "sha-ci"),
                CompareChange("package.json", ChangeStatus.ADDED, "sha-pkg"),
                CompareChange("node_modules/x/readme.md", ChangeStatus.ADDED, "sha-nm"),
            )

        val plan = SyncPlanner.fromCompare(base, "head2", changes, filter)

        assertThat(plan.isEmpty).isTrue()
    }

    @Test
    fun `deleting something the device never had is not a delete`() {
        val base = SyncBase("old", mapOf("a.md" to "sha-a"))
        val changes = listOf(CompareChange("never-had.md", ChangeStatus.REMOVED, null))

        val plan = SyncPlanner.fromCompare(base, "head2", changes, filter)

        assertThat(plan.deletes).isEmpty()
    }
}
