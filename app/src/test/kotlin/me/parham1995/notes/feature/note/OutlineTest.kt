package me.parham1995.notes.feature.note

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.markdown.ReadingTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The outline: it says which section is being read, and a tap goes to another. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class OutlineTest {
    @get:Rule
    val compose = createComposeRule()

    private val headings =
        listOf(
            HeadingEntity(id = 1, noteId = 1, level = 1, text = "Garden", slug = "garden", ordinal = 0, blockIndex = 0),
            HeadingEntity(id = 2, noteId = 1, level = 2, text = "Soil", slug = "soil", ordinal = 1, blockIndex = 5),
            HeadingEntity(id = 3, noteId = 1, level = 2, text = "Water", slug = "water", ordinal = 2, blockIndex = 11),
        )

    @Test
    fun `the section being read is the last heading at or above the top block`() {
        assertThat(currentHeading(headings, 0)).isEqualTo(1)
        assertThat(currentHeading(headings, 7)).isEqualTo(2)
        assertThat(currentHeading(headings, 11)).isEqualTo(3)
        assertThat(currentHeading(headings, 400)).isEqualTo(3)
        assertThat(currentHeading(headings.drop(1), 2)).isNull()
    }

    @Test
    fun `the current section is marked and the others are not`() {
        compose.setContent { OutlineList(headings, current = 2, onJump = {}) }

        compose.onNodeWithText("Soil").assertIsSelected()
        compose.onNodeWithText("Garden").assertIsNotSelected()
        compose.onNodeWithText("Water").assertIsNotSelected()
    }

    @Test
    fun `tapping a heading hands back that heading`() {
        val jumped = mutableListOf<Int>()
        compose.setContent { OutlineList(headings, current = 1, onJump = { jumped += it.blockIndex }) }

        compose.onNodeWithText("Water").performClick()
        assertThat(jumped).containsExactly(11)
    }

    @Test
    fun `the sheet says how long the note is and how long it takes to read`() {
        compose.setContent { ReadingLine(ReadingTime(words = 1_240, minutes = 6)) }

        compose.onNodeWithText("1240 words · 6 min read").assertExists()
    }
}
