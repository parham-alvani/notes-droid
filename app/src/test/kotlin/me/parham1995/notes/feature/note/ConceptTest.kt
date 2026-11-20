package me.parham1995.notes.feature.note

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.ConceptNotice
import me.parham1995.notes.markdown.Okf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * How a concept of the Open Knowledge Format is shown
 * (`open-knowledge-format.md` in notes-linux's `docs/specs/`, section 2): the
 * Concept row's chips say what the front matter says, Mark as Verified
 * reaches its handler, and the reader's line is the link it says it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ConceptTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-09-23T00:00:00Z")

    private fun concept(frontMatter: String) = Okf.conceptOf("---\n$frontMatter\n---\n", "Docs/x.md")!!

    @Test
    fun `a reviewed, stable, fresh concept shows its type and tier, and can be marked`() {
        var marked = 0
        compose.setContent {
            ConceptRow(
                concept =
                    concept(
                        "type: Metric\nverified: { by: \"human:parham\", at: \"2026-06-25T09:00:00Z\" }\n" +
                            "stale_after: 2027-01-01",
                    ),
                onVerify = { marked++ },
                now = now,
            )
        }

        compose.onNodeWithText("Concept · Metric").assertExists()
        compose.onNodeWithText("Human-reviewed").assertExists()
        // Who and when, for a screen reader as for a long press.
        compose.onNodeWithContentDescription("human:parham", substring = true).assertExists()
        listOf("Draft", "Deprecated").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("Stale", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Mark as Verified").performClick()
        assertThat(marked).isEqualTo(1)
    }

    @Test
    fun `a deprecated, stale, unverified concept says all three, and one that cannot be written has no button`() {
        compose.setContent {
            ConceptRow(
                concept = concept("type: Table\nstatus: deprecated\nstale_after: \"soon\""),
                onVerify = null,
                now = now,
            )
        }

        compose.onNodeWithText("Unverified").assertExists()
        compose.onNodeWithText("Deprecated").assertExists()
        compose.onNodeWithText("Stale: bad date").assertExists()
        compose.onNodeWithText("Mark as Verified").assertDoesNotExist()
    }

    @Test
    fun `a status no list knows is shown as written, and a write under way holds the button`() {
        compose.setContent {
            ConceptRow(
                concept =
                    concept(
                        "type: Table\nstatus: archived\n" +
                            "verified: { by: \"process:nightly\", at: \"2026-06-25T09:00:00Z\" }",
                    ),
                onVerify = {},
                enabled = false,
                now = now,
            )
        }

        compose.onNodeWithText("archived").assertExists()
        compose.onNodeWithText("Machine-confirmed").assertExists()
        compose.onNodeWithText("Mark as Verified").assertIsNotEnabled()
    }

    @Test
    fun `the line above a deprecated concept is the link to what replaced it`() {
        val opened = mutableListOf<Long>()
        compose.setContent { ConceptLine(ConceptNotice.Deprecated(7L to "Orders v2"), onOpen = { opened += it }) }

        compose.onNodeWithText("Deprecated. Replaced by Orders v2.").assertExists()
        compose.onNodeWithTag(CONCEPT_LINE).performClick()

        assertThat(opened).containsExactly(7L)
    }

    @Test
    fun `with nothing to replace it the line says deprecated and goes nowhere`() {
        val opened = mutableListOf<Long>()
        compose.setContent { ConceptLine(ConceptNotice.Deprecated(null), onOpen = { opened += it }) }

        compose.onNodeWithText("Deprecated.").assertExists()
        compose.onNodeWithTag(CONCEPT_LINE).performClick()

        assertThat(opened).isEmpty()
    }

    @Test
    fun `the line above a stale concept says since when, or that the date is bad`() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                ConceptLine(ConceptNotice.Stale(Instant.parse("2026-06-30T12:00:00Z")), onOpen = {})
                ConceptLine(ConceptNotice.Stale(null), onOpen = {})
            }
        }

        compose.onNodeWithText("Stale since", substring = true).assertExists()
        compose.onNodeWithText("2026", substring = true).assertExists()
        compose.onNodeWithText("Stale, with a date that cannot be read: check it again.").assertExists()
    }
}
