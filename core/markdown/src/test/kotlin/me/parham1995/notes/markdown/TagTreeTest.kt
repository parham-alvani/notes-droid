package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagTreeTest {
    @Test
    fun `a parent counts each note under it once`() {
        val tree =
            TagTree.build(
                listOf(
                    "project/alpha" to 1L,
                    "project/beta" to 1L,
                    "project/beta" to 2L,
                    "idea" to 3L,
                ),
            )
        assertThat(tree.map { it.path to it.count }).containsExactly("idea" to 1, "project" to 2).inOrder()
        val project = tree.single { it.path == "project" }
        assertThat(project.children.map { it.label to it.count }).containsExactly("alpha" to 1, "beta" to 2).inOrder()
    }

    @Test
    fun `case does not make two tags`() {
        val tree = TagTree.build(listOf("Idea" to 1L, "idea" to 2L, "IDEA/sub" to 3L))
        assertThat(tree.map { it.path to it.count }).containsExactly("Idea" to 3)
        assertThat(
            tree
                .single()
                .children
                .single()
                .path,
        ).isEqualTo("Idea/sub")
    }

    @Test
    fun `flattening keeps depth for indenting`() {
        val rows = TagTree.flatten(TagTree.build(listOf("a/b/c" to 1L)))
        assertThat(rows.map { it.first.label to it.second }).containsExactly("a" to 0, "b" to 1, "c" to 2).inOrder()
    }
}
