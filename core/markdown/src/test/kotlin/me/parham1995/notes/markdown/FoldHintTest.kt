package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a folded heading says about the section under it.
 *
 * The first five tests are the table every Daftar app must pass
 * (`folded-sections.md` in notes-linux's `docs/specs/`, and
 * `web/test/folds.test.mjs` in the other two apps); the rest are how the
 * section is counted from a note's blocks.
 */
class FoldHintTest {
    @Test
    fun `a folded section says how its text begins and how much is under it`() {
        assertThat(FoldHint.text("Coffee and tea are fine.", 240, 3))
            .isEqualTo("Coffee and tea are fine. · 240 words · 3 sections")
        assertThat(FoldHint.text("One line only.", 3, 0)).isEqualTo("One line only.")
        assertThat(FoldHint.text("One line only.", 3, 1)).isEqualTo("One line only. · 1 section")
    }

    @Test
    fun `a long beginning is cut at a word, with no mark left before the dots`() {
        val long =
            "Because it is a mail drop it cannot carry the empadronamiento, which needs the address we sleep at."
        val hint = FoldHint.text(long, 18, 0)
        assertThat(hint).isEqualTo("Because it is a mail drop it cannot carry the empadronamiento… · 18 words")
        assertThat(hint.substringBefore(" · ").length).isAtMost(FoldHint.LIMIT + 1)
    }

    @Test
    fun `one long word is cut where the room ends`() {
        assertThat(FoldHint.text("x".repeat(80), 1, 0)).isEqualTo("x".repeat(64) + "… · 1 word")
    }

    @Test
    fun `a section of headings only says how many, and an empty one says nothing but the dots`() {
        assertThat(FoldHint.text("", 0, 4)).isEqualTo("4 sections")
        assertThat(FoldHint.text("", 0, 0)).isEqualTo("…")
        assertThat(FoldHint.text("   ", 0, 0)).isEqualTo("…")
    }

    @Test
    fun `Persian text is cut at a word too, and its comma is not left hanging`() {
        val text = "امروز صبح زود بیدار شدم، قهوه خوردم و بعد از آن برای پیاده‌روی به کنار ساحل رفتم و برگشتم."
        val hint = FoldHint.text(text, 20, 0)
        assertThat(hint).endsWith("… · 20 words")
        assertThat(hint).doesNotContainMatch("""[،\s]…""")
    }

    private fun blocks(markdown: String): List<MdBlock> = MarkdownParser.parseNote(markdown).blocks

    private val trip =
        blocks(
            """
            # Trip

            The plan of the week.

            ## Flights

            We fly on Monday morning from Barcelona and come back on Friday night.

            ### Seats

            Two by the window.

            ## Hotel

            One room.

            ## Empty
            """.trimIndent(),
        )
    // Positions: 0 Trip, 1 plan, 2 Flights, 3 fly, 4 Seats, 5 two, 6 Hotel, 7 room, 8 Empty.

    @Test
    fun `a heading counts everything down to the next heading as big, and the headings on the way`() {
        assertThat(FoldHint.of(trip, 0)?.say()).isEqualTo("The plan of the week. · 24 words · 4 sections")
        assertThat(FoldHint.of(trip, 2)?.say())
            .isEqualTo("We fly on Monday morning from Barcelona and come back on Friday… · 17 words · 1 section")
        // The beginning shows every word, so they are not counted aloud.
        assertThat(FoldHint.of(trip, 4)?.say()).isEqualTo("Two by the window.")
        assertThat(FoldHint.of(trip, 6)?.say()).isEqualTo("One room.")
    }

    @Test
    fun `a heading over headings only says how many, and one over nothing the dots`() {
        val note = blocks("# Index\n\n## One\n\n## Two\n\ntext\n")
        assertThat(FoldHint.of(note, 0)?.say()).isEqualTo("text · 2 sections")
        assertThat(FoldHint.of(blocks("# Index\n\n## One\n\n## Two\n"), 0)?.say()).isEqualTo("2 sections")
        assertThat(FoldHint.of(trip, 8)?.say()).isEqualTo("…")
    }

    @Test
    fun `the counts can be said in other words, and the parts joined another way`() {
        val hint = FoldHint.of(trip, 2)!!
        assertThat(hint.words).isEqualTo(17)
        assertThat(hint.sections).isEqualTo(1)
        assertThat(hint.join(words = "17 واژه", sections = "1 بخش", separator = ", "))
            .isEqualTo("We fly on Monday morning from Barcelona and come back on Friday…, 17 واژه, 1 بخش")
        // What the hint leaves out is not there to be said.
        val short = FoldHint.of(trip, 6)!!
        assertThat(short.words).isNull()
        assertThat(short.sections).isNull()
        assertThat(short.isEmpty).isFalse()
        assertThat(FoldHint.of(trip, 8)!!.isEmpty).isTrue()
    }

    @Test
    fun `a block that is no heading has no hint`() {
        assertThat(FoldHint.of(trip, 1)).isNull()
        assertThat(FoldHint.of(trip, 99)).isNull()
    }

    @Test
    fun `the beginning is the first block with words, read as the page shows it`() {
        val note =
            blocks(
                """
                ## Plan

                ---

                - [ ] call the **bank**
                - [x] pay [[Rent|the rent]]

                | Day | Hours |
                | --- | --- |
                | Mon | 8 |

                A line that wraps
                onto the next one.
                """.trimIndent(),
            )
        // The rule has no words; the list is where the text begins. Markup,
        // the task boxes and a link's target are not words.
        assertThat(FoldHint.of(note, 0)?.say()).isEqualTo("call the bank pay the rent · 18 words")
    }

    @Test
    fun `front matter above the first heading is not text under it`() {
        val note = blocks("---\ntags: [a, b]\n---\n\n# Title\n\nHello there.\n")
        val title = note.indexOfFirst { it is MdBlock.Heading }
        assertThat(FoldHint.of(note, title)?.say()).isEqualTo("Hello there.")
    }
}
