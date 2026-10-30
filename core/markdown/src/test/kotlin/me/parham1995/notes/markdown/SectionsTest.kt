package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The outline's closed headings and the sort of the headings under one, as the Mac does them. */
class SectionsTest {
    // # A, ## A1, ### A1a, ## A2, # B, ## B1, # C
    private val levels = listOf(1, 2, 3, 2, 1, 2, 1)

    private fun visible(vararg closed: Int) = OutlineTree.visible(levels) { it in closed }

    @Test
    fun `a closed heading hides the headings under it`() {
        assertThat(OutlineTree.hasChildren(levels, 0)).isTrue()
        assertThat(OutlineTree.hasChildren(levels, 1)).isTrue()
        assertThat(OutlineTree.hasChildren(levels, 2)).isFalse()
        assertThat(OutlineTree.hasChildren(levels, 6)).isFalse()
        assertThat(visible()).isEqualTo((0..6).toList())
        assertThat(visible(0)).containsExactly(0, 4, 5, 6).inOrder()
        assertThat(visible(1)).containsExactly(0, 1, 3, 4, 5, 6).inOrder()
        // A heading closed under a closed one stays closed when that opens.
        assertThat(visible(0, 1, 4)).containsExactly(0, 4, 6).inOrder()
        // A heading with nothing under it has nothing to hide.
        assertThat(visible(2, 6)).isEqualTo((0..6).toList())
        // The section inside a closed heading marks that heading.
        assertThat(OutlineTree.shown(2, visible(0))).isEqualTo(0)
        assertThat(OutlineTree.shown(5, visible(0))).isEqualTo(5)
        assertThat(OutlineTree.key(2, "A1")).isEqualTo("2:A1")
    }

    private val note =
        """
        ---
        title: x
        # not a heading
        ---
        # Plants

        Intro stays first.

        ## Tomato

        Red.

        ### Cherry tomato

        ```
        ## not a heading
        ```

        ## apple

        Green. %% ## not a heading %%

        ## Bean 10

        ## Bean 9
        Tall.

        # Kitchen

        ## Zucchini

        """.trimIndent()

    @Test
    fun `the headings are read outside fences comments and the front matter`() {
        assertThat(Sections.headings(note).map { it.text })
            .containsExactly("Plants", "Tomato", "Cherry tomato", "apple", "Bean 10", "Bean 9", "Kitchen", "Zucchini")
            .inOrder()
    }

    @Test
    fun `the headings under one are sorted with their sections`() {
        val sorted = Sections.sortChildren(note, 0)!!
        assertThat(Sections.headings(sorted).map { it.text })
            .containsExactly("Plants", "apple", "Bean 9", "Bean 10", "Tomato", "Cherry tomato", "Kitchen", "Zucchini")
            .inOrder()
        assertThat(sorted).contains("# Plants\n\nIntro stays first.\n\n## apple\n\nGreen.")
        assertThat(sorted).contains("## Tomato\n\nRed.\n\n### Cherry tomato\n\n```\n## not a heading\n```\n")
        assertThat(sorted).contains("## Bean 9\nTall.\n")
        assertThat(sorted.endsWith("\n")).isTrue()
        // Every line is still there, once.
        assertThat(
            sorted
                .lines()
                .filter {
                    it.isNotBlank()
                }.sorted(),
        ).isEqualTo(note.lines().filter { it.isNotBlank() }.sorted())
        assertThat(Sections.sortChildren(sorted, 0)).isNull()
        // One heading under it, or none: nothing to sort.
        assertThat(Sections.sortChildren(note, 6)).isNull()
        assertThat(Sections.sortChildren(note, 7)).isNull()
    }

    @Test
    fun `a heading picked in the outline is found in the file by its text`() {
        val headings = Sections.headings("# A\n## [[Plan|The plan]]\n## **Bold** `x`\n# B\n## [[Plan|The plan]]\n")
        assertThat(Sections.plain("[[Plan|The plan]]")).isEqualTo("The plan")
        assertThat(Sections.find(headings, 2, "The plan", 0)).isEqualTo(1)
        assertThat(Sections.find(headings, 2, "The plan", 1)).isEqualTo(4)
        assertThat(Sections.find(headings, 2, "Bold x", 0)).isEqualTo(2)
        assertThat(Sections.find(headings, 2, "Gone", 0)).isNull()
    }
}
