package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The quotes a quote widget finds in a note, and which one each day shows. */
class QuotesTest {
    private fun parse(markdown: String) = Quotes.parse(markdown.trimIndent())

    @Test
    fun `each quote callout is one quote, in the note's order`() {
        val quotes =
            parse(
                """
                > [!quote]
                >
                > The kettle boils sooner when watched closely.

                > [!quote]
                >
                > A short walk is still a walk.
                """,
            )
        assertThat(quotes.map { it.lines })
            .containsExactly(
                listOf("The kettle boils sooner when watched closely."),
                listOf("A short walk is still a walk."),
            ).inOrder()
    }

    @Test
    fun `a closing dash line is the source, not part of the words`() {
        val quote =
            parse(
                """
                > [!quote]
                >
                > Small steps, every day.
                >
                > — A. Walker, _Notes on Hills_
                """,
            ).single()
        assertThat(quote.lines).containsExactly("Small steps, every day.")
        assertThat(quote.source).isEqualTo("A. Walker, Notes on Hills")
    }

    @Test
    fun `an en dash and two hyphens name a source too`() {
        assertThat(parse("> [!quote]\n>\n> Words.\n>\n> – Someone").single().source).isEqualTo("Someone")
        assertThat(parse("> [!quote]\n>\n> Words.\n>\n> -- Someone").single().source).isEqualTo("Someone")
    }

    @Test
    fun `a quote of several paragraphs keeps them apart`() {
        val quote = parse("> [!quote]\n>\n> First.\n>\n> Second.\n>\n> Third.").single()
        assertThat(quote.lines).containsExactly("First.", "Second.", "Third.").inOrder()
        assertThat(quote.source).isNull()
    }

    @Test
    fun `a lone dash line is the quote, not an empty one with a source`() {
        val quote = parse("> [!quote]\n>\n> — only this").single()
        assertThat(quote.lines).containsExactly("— only this")
        assertThat(quote.source).isNull()
    }

    @Test
    fun `markup is read as the words it marks`() {
        val quote = parse("> [!quote]\n>\n> Be **bold**, ==mark it==, see [[Other Note|the other]].").single()
        assertThat(quote.lines).containsExactly("Be bold, mark it, see the other.")
    }

    @Test
    fun `a quote written in the title slot alone still counts`() {
        assertThat(parse("> [!quote] Keep going").single().lines).containsExactly("Keep going")
    }

    @Test
    fun `everything that is not a quote callout is left out`() {
        val quotes =
            parse(
                """
                An introduction that is not a quote.

                ## Section

                > A plain blockquote.

                > [!note]
                >
                > A note callout.

                > [!quote]
                >
                > The one quote.
                """,
            )
        assertThat(quotes.map { it.lines }).containsExactly(listOf("The one quote."))
    }

    @Test
    fun `a quote inside a list item is found`() {
        assertThat(parse("- item\n\n    > [!quote]\n    >\n    > Nested.").map { it.lines })
            .containsExactly(listOf("Nested."))
    }

    @Test
    fun `no quotes, no quote for the day`() {
        assertThat(Quotes.forDay(emptyList(), 20_000)).isNull()
    }

    @Test
    fun `the same day always gives the same quote`() {
        val quotes = (1..7).map { Quote(listOf("q$it")) }
        assertThat(Quotes.forDay(quotes, 20_382)).isEqualTo(Quotes.forDay(quotes, 20_382))
    }

    @Test
    fun `every quote comes round once before any comes round twice`() {
        val quotes = (1..7).map { Quote(listOf("q$it")) }
        // Seven days starting on a round's first day: all seven, each once.
        val round = (0L until 7L).map { Quotes.forDay(quotes, 7 * 2_911 + it) }
        assertThat(round).containsExactlyElementsIn(quotes)
    }

    @Test
    fun `skipping moves to the next day's quote`() {
        val quotes = (1..5).map { Quote(listOf("q$it")) }
        assertThat(Quotes.forDay(quotes, 100, skip = 1)).isEqualTo(Quotes.forDay(quotes, 101))
    }

    @Test
    fun `days before the epoch still pick a quote`() {
        val quotes = (1..3).map { Quote(listOf("q$it")) }
        assertThat(Quotes.forDay(quotes, -1)).isNotNull()
    }
}
