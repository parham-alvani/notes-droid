package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a voice reading a note says, and in which language. Synthetic. */
class SpokenTextTest {
    private fun spoken(markdown: String) = SpokenText.of(MarkdownParser.parseNote(markdown).blocks)

    @Test
    fun `the words are read in order and the machinery is not`() {
        val said =
            spoken(
                """
                ---
                tags: [x]
                ---
                # Harvest

                Pick the **red** ones first, see [[Tomato]].

                ```bash
                kubectl get pods
                ```

                - one
                - [ ] two
                """.trimIndent(),
            ).map { it.text }

        assertThat(said).containsExactly("Harvest", "Pick the red ones first, see Tomato.", "one", "two").inOrder()
    }

    @Test
    fun `a table is read a row at a time`() {
        val said = spoken("| Item | Cost |\n|---|---|\n| Rent | 1450 |").map { it.text }

        assertThat(said).containsExactly("Item, Cost", "Rent, 1450").inOrder()
    }

    @Test
    fun `each paragraph gets the voice of its own language`() {
        val said = spoken("An English line.\n\nاین یک خط فارسی است.\n\nThe word کار in English.")

        assertThat(said.map { it.persian }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun `a paragraph too long for one utterance is cut at a sentence`() {
        val sentence = "This sentence is here to be long enough. "
        val said = spoken(sentence.repeat(200)).map { it.text }

        assertThat(said.size).isGreaterThan(1)
        assertThat(said.all { it.length <= 3000 && it.endsWith(".") }).isTrue()
        assertThat(said.joinToString(" ")).isEqualTo(sentence.repeat(200).trim())
    }

    @Test
    fun `each utterance knows which block to follow along to`() {
        val said = spoken("# Title\n\n```\ncode\n```\n\n- one\n- two\n\nAfter.")

        // The code block is block 1 and says nothing; the list is block 2.
        assertThat(said.map { it.text to it.block })
            .containsExactly("Title" to 0, "one" to 2, "two" to 2, "After." to 3)
            .inOrder()
    }
}
