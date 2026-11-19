package me.parham1995.notes.ui.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.FoldSections
import me.parham1995.notes.markdown.HeadingFolds
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.MdBlock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Notes that open folded, through the real page, on a made-up note: one the
 * person has not folded opens with every section shut and each heading
 * saying what it holds; one they folded or opened opens as they left it
 * (`folded-sections.md` in notes-linux's `docs/specs/`, and the same four
 * cases as `FoldedNotesTests` on the Mac).
 *
 * The page is given its folds the way the note screen gives them: what the
 * note opens with is [HeadingFolds.opening] of what is kept for it and the
 * vault's and the app's word, and a tap on a chevron is kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FoldedNoteRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private val markdown =
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
        """.trimIndent()

    private val blocks = MarkdownParser.parseNote(markdown).blocks

    private var folded by mutableStateOf(emptySet<Int>())

    /** What the note screen would keep for the note: null until the person folds or opens something. */
    private var kept: Set<Int>? = null

    /** Opens the note: [kept] is the list kept for it, the vault says its notes open folded. */
    private fun open(
        kept: Set<Int>? = null,
        app: FoldSections = FoldSections.VAULT,
        vault: Boolean = true,
    ) {
        this.kept = kept
        folded = HeadingFolds.opening(blocks, kept, app.folds(vault))
        compose.setContent {
            MarkdownDocument(
                blocks = blocks,
                actions = RenderActions(),
                folded = folded,
                onToggleFold = { index ->
                    folded = if (index in folded) folded - index else folded + index
                    this.kept = folded
                },
            )
        }
    }

    private fun position(heading: String): Int = blocks.indexOfFirst { it is MdBlock.Heading && it.text == heading }

    /** The hints on the page, as they are written. */
    private fun hints(): List<String> =
        compose
            .onAllNodesWithTag(FOLD_HINT_TAG)
            .fetchSemanticsNodes()
            .map {
                it.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .joinToString("") { text -> text.text }
            }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** The text [text] as the note's own, not as a shut heading's hint. */
    private fun body(text: String) = compose.onAllNodes(hasText(text) and !hasTestTag(FOLD_HINT_TAG))

    @Test
    fun `a note not folded by the person opens with every section shut`() {
        open()

        // Every heading that holds something; "Empty" holds nothing and has no fold.
        assertThat(folded).containsExactly(position("Trip"), position("Flights"), position("Seats"), position("Hotel"))
        // Under the first heading everything is away: only the note's own heading is on the page.
        compose.onNodeWithText("Trip").assertExists()
        compose.onNodeWithText("Flights").assertDoesNotExist()
        body("The plan of the week.").assertCountEquals(0)
        compose.onAllNodes(isHeading).assertCountEquals(1)
        // The heading says how its text begins, how much is under it, and how many headings.
        assertThat(hints()).containsExactly("The plan of the week. · 24 words · 4 sections")
        // Nothing was folded by the person, so nothing is kept for the note.
        assertThat(kept).isNull()
    }

    @Test
    fun `the hint is not the heading's name, and a screen reader is told it as a sentence`() {
        open()

        // The heading is still "Trip", to the outline, a link and a screen reader.
        val heading = compose.onAllNodes(isHeading).fetchSemanticsNodes().single()
        assertThat(
            heading.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString("") { it.text },
        ).isEqualTo("Trip")
        assertThat((blocks[position("Trip")] as MdBlock.Heading).text).isEqualTo("Trip")
        // The hint is a node of its own after it, read with commas, not dots.
        compose
            .onAllNodesWithContentDescription("The plan of the week., 24 words, 4 sections")
            .assertCountEquals(1)
        compose
            .onAllNodes(
                isHeading and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            ).assertCountEquals(0)
    }

    @Test
    fun `opening a section shows the next level folded and is kept`() {
        open()

        compose.onAllNodesWithContentDescription("Unfold section")[0].performClick()

        assertThat(folded).containsExactly(position("Flights"), position("Seats"), position("Hotel"))
        // Its text and its own headings, shut: what is under them is still away.
        compose.onNodeWithText("The plan of the week.").assertExists()
        compose.onNodeWithText("Flights").assertExists()
        compose.onNodeWithText("Hotel").assertExists()
        compose.onNodeWithText("Empty").assertExists()
        compose.onNodeWithText("Seats").assertDoesNotExist()
        body("One room.").assertCountEquals(0)
        // The opened heading says nothing more; the shut ones under it do.
        assertThat(hints())
            .containsExactly(
                "We fly on Monday morning from Barcelona and come back on Friday… · 17 words · 1 section",
                "One room.",
            ).inOrder()
        // What the person did is kept for the note.
        assertThat(kept).containsExactly(position("Flights"), position("Seats"), position("Hotel"))
    }

    @Test
    fun `a note the person opened whole stays open`() {
        // An empty list, kept: not the same as no list.
        open(kept = emptySet())

        assertThat(folded).isEmpty()
        compose.onNodeWithText("Two by the window.").assertExists()
        compose.onAllNodesWithTag(FOLD_HINT_TAG).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Unfold section").assertCountEquals(0)
    }

    @Test
    fun `a note the person folded opens with exactly those shut`() {
        open(kept = setOf(position("Hotel")))

        assertThat(folded).containsExactly(position("Hotel"))
        compose.onNodeWithText("Two by the window.").assertExists()
        // Its one line is away, and the heading says it instead.
        body("One room.").assertCountEquals(0)
        assertThat(hints()).containsExactly("One room.")
    }

    @Test
    fun `where notes open unfolded nothing is shut`() {
        open(app = FoldSections.NEVER)

        assertThat(folded).isEmpty()
        compose.onNodeWithText("One room.").assertExists()
        compose.onAllNodesWithTag(FOLD_HINT_TAG).assertCountEquals(0)
    }

    @Test
    fun `the app can fold the notes of a vault that does not ask for it`() {
        open(app = FoldSections.ALWAYS, vault = false)

        assertThat(hints()).containsExactly("The plan of the week. · 24 words · 4 sections")
    }

    @Test
    fun `a vault that says nothing opens its notes whole`() {
        open(vault = false)

        assertThat(folded).isEmpty()
        compose.onAllNodesWithTag(FOLD_HINT_TAG).assertCountEquals(0)
    }
}
