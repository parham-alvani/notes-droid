package me.parham1995.notes.ui.render

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.FootnoteEntry
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.Transclusion
import me.parham1995.notes.markdown.footnotes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `![[Another note]]` on the page.
 *
 * It used to be an attachment card promising "another app" and delivering
 * nothing. What matters is that the other note's words are actually drawn,
 * and that its links and tasks act on the note they were written in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NoteEmbedTest {
    @get:Rule
    val compose = createComposeRule()

    private val plan =
        MarkdownParser
            .parseNote("# Plan\n\nintro\n\n## Open items\n\n- [ ] ship it\n- see [[Budget]]\n\n## Done\n\nold stuff\n")
            .blocks

    private fun render(
        markdown: String,
        actions: RenderActions,
    ) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        compose.setContent { blocks.forEach { MdBlockView(it, actions, emptySet()) } }
    }

    private fun transcluding(
        onNoteLink: (Long, String?) -> Unit = { _, _ -> },
        onCompleteTask: ((Int) -> Unit)? = null,
    ) = RenderActions(
        inline = InlineActions(onNoteLink = onNoteLink),
        onCompleteTask = onCompleteTask,
        transclude = { target, heading ->
            if (target != "Plan") {
                null
            } else {
                Transclusion.section(plan, heading)?.let {
                    Transcluded(7, "Plan", it, mapOf("Budget" to 42L), emptySet())
                }
            }
        },
    )

    @Test
    fun `the embedded section is drawn in place`() {
        render("![[Plan#Open items]]", transcluding())

        compose.onNodeWithText("ship it").assertExists()
        // The section, not the whole note.
        compose.onNodeWithText("old stuff").assertDoesNotExist()
        compose.onNodeWithText("intro").assertDoesNotExist()
    }

    @Test
    fun `a link inside the embed resolves against the embedded note`() {
        val opened = mutableListOf<Long>()
        render("![[Plan]]", transcluding(onNoteLink = { id, _ -> opened += id }))

        compose.onNodeWithText("see Budget", substring = true).performFirstLinkClick()
        compose.waitForIdle()
        assertThat(opened).containsExactly(42L)
    }

    @Test
    fun `a task inside the embed cannot be ticked from here`() {
        val ticked = mutableListOf<Int>()
        render("![[Plan]]", transcluding(onCompleteTask = { ticked += it }))

        compose.onNodeWithContentDescription("unchecked").performClick()
        assertThat(ticked).isEmpty()
    }

    @Test
    fun `an embed of nothing says so rather than drawing a blank`() {
        render("![[Nowhere]]", transcluding())

        compose.onNodeWithText("Nowhere").assertExists()
        compose.onNodeWithText("This link goes nowhere yet").assertExists()
    }

    @Test
    fun `a footnote in an embed is the embedded note's own`() {
        // Both notes use the label `1`; the embedded one means something else.
        val cited = MarkdownParser.parseNote("## Claim\n\nIt holds[^1].\n\n[^1]: The embedded source.")
        val shown = mutableListOf<String>()
        val actions =
            RenderActions(
                showFootnote = { entry -> shown += plainText(entry) },
                transclude = { _, heading ->
                    Transclusion.section(cited.blocks, heading)?.let {
                        Transcluded(7, "Cited", it, emptyMap(), emptySet(), cited.blocks.footnotes())
                    }
                },
            )
        render("Outer[^1].\n\n![[Cited#Claim]]\n\n[^1]: The outer source.", actions)

        compose.onNodeWithText("It holds1.").performFirstLinkClick()
        compose.waitForIdle()
        assertThat(shown).containsExactly("The embedded source.")
    }

    private fun plainText(entry: FootnoteEntry): String =
        entry.blocks
            .filterIsInstance<MdBlock.Paragraph>()
            .joinToString(" ") {
                me.parham1995.notes.markdown
                    .plainText(it.inlines)
            }
}
