package me.parham1995.notes.feature.note

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.PeriodNeighbours
import me.parham1995.notes.data.database.NoteRef
import me.parham1995.notes.obsidian.Period
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The strip that steps along a journal: there on a daily note and nowhere
 * else, and a tap hands back the note it names -- the layer where this app's
 * features have shipped drawn and inert. Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PeriodBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<Long>()

    private fun render(neighbours: PeriodNeighbours?) {
        compose.setContent { PeriodBar(neighbours, onOpen = { opened += it }) }
    }

    @Test
    fun `an ordinary note has no way along a journal`() {
        render(null)

        compose.onAllNodesWithContentDescription("Previous", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Next", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("No earlier note").assertCountEquals(0)
    }

    @Test
    fun `each arrow opens the note it names, across a gap`() {
        render(
            PeriodNeighbours(
                vaultId = 1L,
                period = Period.WEEK,
                previous = NoteRef(35L, "2026-W35.md"),
                next = NoteRef(40L, "Journal/2026-W40.md"),
            ),
        )

        // Named, so a jump of four weeks is seen before it is taken.
        compose.onNodeWithText("2026-W35").assertIsEnabled()
        compose.onNodeWithContentDescription("Previous: 2026-W35").performClick()
        compose.onNodeWithText("2026-W40").performClick()

        assertThat(opened).containsExactly(35L, 40L).inOrder()
    }

    @Test
    fun `the end of a journal is a disabled arrow, and taps nothing`() {
        render(PeriodNeighbours(1L, Period.WEEK, previous = NoteRef(38L, "2026-W38.md"), next = null))

        compose.onNodeWithContentDescription("No later note").assertIsNotEnabled().performClick()
        assertThat(opened).isEmpty()
        compose.onNodeWithContentDescription("Previous: 2026-W38").assertIsEnabled()
    }
}
