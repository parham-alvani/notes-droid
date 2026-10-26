package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every fixture here is made up. The repository is public; the vault is not. */
class QuickSwitcherTest {
    private val paths =
        listOf(
            "Learning/Infrastructure/Kubernetes/Kubernetes.md",
            "Learning/Infrastructure/Kubernetes/Firewall rules for Kubernetes.md",
            "Learning/Infrastructure/Kubernetes/Kubectl.md",
            "Companies/Docker/Docker.md",
            "Companies/Docker/Interview.md",
            "Companies/Acme/Interview.md",
            "Notes/Inbox.md",
            "Notes/Kebab recipes.md",
        )
    private val notes =
        paths.mapIndexed { index, path ->
            QuickSwitcher.Candidate(
                id = index.toLong(),
                path = path,
                title = path.substringAfterLast('/').removeSuffix(".md"),
                aliases = if (path == "Notes/Inbox.md") listOf("In tray") else emptyList(),
            )
        }

    private fun top(query: String) = QuickSwitcher.rank(query, notes).map { paths[it.toInt()] }

    @Test
    fun `the exact name comes first`() {
        assertThat(top("kubernetes").first()).isEqualTo("Learning/Infrastructure/Kubernetes/Kubernetes.md")
        assertThat(top("kubernetes")).contains("Learning/Infrastructure/Kubernetes/Firewall rules for Kubernetes.md")
    }

    @Test
    fun `words match in any order`() {
        assertThat(top("fire kube").first()).endsWith("Firewall rules for Kubernetes.md")
        assertThat(top("kub zzz")).isEmpty()
    }

    @Test
    fun `a folder word picks one of two notes with the same name`() {
        assertThat(top("acme interview").first()).isEqualTo("Companies/Acme/Interview.md")
        assertThat(top("docker interv").first()).isEqualTo("Companies/Docker/Interview.md")
    }

    @Test
    fun `a path finds its note and a folder lists what is in it`() {
        assertThat(top("Companies/Docker/Interview").first()).isEqualTo("Companies/Docker/Interview.md")
        assertThat(top("comp/acme").first()).isEqualTo("Companies/Acme/Interview.md")
        assertThat(
            top("Companies/Docker/"),
        ).containsExactly("Companies/Docker/Docker.md", "Companies/Docker/Interview.md")
        assertThat(top("notes/")).containsExactly("Notes/Inbox.md", "Notes/Kebab recipes.md")
    }

    @Test
    fun `an alias finds its note`() {
        assertThat(top("in tray").first()).isEqualTo("Notes/Inbox.md")
    }

    @Test
    fun `letters spread over a path are not results once a note holds the words`() {
        assertThat(top("kebab")).containsExactly("Notes/Kebab recipes.md")
        assertThat(top("docker")).doesNotContain("Learning/Infrastructure/Kubernetes/Kubernetes.md")
        // Nothing holds the word: the spread-out letters are all there is.
        val spread = QuickSwitcher.Candidate(1, "Reinsurance Group of America.md", "Reinsurance Group of America")
        assertThat(QuickSwitcher.rank("car", listOf(spread))).containsExactly(1L)
    }
}
