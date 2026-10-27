package me.parham1995.notes.feature.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a share from another app puts in the capture field: the line it should
 * be in the vault, not the two extras it arrived in glued together.
 */
class CaptureShareTest {
    @Test
    fun `plain text is kept as it was`() {
        assertThat(sharedCapture(null, "  buy oat milk  ")).isEqualTo("buy oat milk")
    }

    @Test
    fun `a page shared as a title and a URL is a link to it`() {
        assertThat(sharedCapture("The quick brown fox", "https://example.com/fox"))
            .isEqualTo("[The quick brown fox](https://example.com/fox)")
    }

    @Test
    fun `the title again in the text makes the same link, not the title twice`() {
        assertThat(sharedCapture("Fox", "Fox https://example.com/fox")).isEqualTo("[Fox](https://example.com/fox)")
        assertThat(sharedCapture("Fox", "Fox\nhttps://example.com/fox")).isEqualTo("[Fox](https://example.com/fox)")
        assertThat(sharedCapture(null, "Fox\nhttps://example.com/fox")).isEqualTo("[Fox](https://example.com/fox)")
    }

    @Test
    fun `a URL on its own is an autolink, which markdownlint accepts`() {
        assertThat(sharedCapture(null, "https://example.com/a")).isEqualTo("<https://example.com/a>")
        assertThat(sharedCapture("https://example.com/a", "https://example.com/a"))
            .isEqualTo("<https://example.com/a>")
    }

    @Test
    fun `a sentence ending in a URL stays a sentence`() {
        assertThat(sharedCapture(null, "look at this https://example.com/a"))
            .isEqualTo("look at this <https://example.com/a>")
    }

    @Test
    fun `brackets in a title and parentheses in a URL do not break the link`() {
        assertThat(sharedCapture("Fox [draft]", "https://example.org/wiki/Fox_(animal)"))
            .isEqualTo("[Fox \\[draft\\]](<https://example.org/wiki/Fox_(animal)>)")
    }

    @Test
    fun `several lines stay several lines, under their subject`() {
        assertThat(sharedCapture("Recipe", "flour\nwater\n\nsalt")).isEqualTo("Recipe\nflour\nwater\n\nsalt")
        assertThat(sharedCapture(null, "flour\nwater")).isEqualTo("flour\nwater")
    }

    @Test
    fun `one line of text keeps its subject in front, once`() {
        assertThat(sharedCapture("Note", "call the bank")).isEqualTo("Note - call the bank")
        assertThat(sharedCapture("Note", "Note: call the bank")).isEqualTo("Note: call the bank")
        assertThat(sharedCapture("Only a subject", "")).isEqualTo("Only a subject")
    }
}
