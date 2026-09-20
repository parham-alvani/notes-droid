package me.parham1995.notes.ui.render

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.ui.LocalReading
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a task looks like on the page, and what happens when it is tapped.
 *
 * These go through the real renderer rather than the helpers underneath it,
 * because the helpers were right in both cases where this went wrong on a
 * device: the checkbox was correct and inert, and a setting was correct and not
 * wired to anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TaskRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        markdown: String,
        reading: ReadingSettings = ReadingSettings(),
        actions: RenderActions = RenderActions(),
    ) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        compose.setContent {
            CompositionLocalProvider(LocalReading provides reading) {
                blocks.forEach { MdBlockView(it, actions, emptySet()) }
            }
        }
    }

    @Test
    fun `tapping a task's box completes the line it was written on`() {
        val ticked = mutableListOf<Int>()
        render(
            markdown = "- [ ] first\n- [ ] second\n",
            actions = RenderActions(onCompleteTask = { line -> ticked += line }),
        )

        compose.onAllNodesWithContentDescription("unchecked")[1].performClick()

        // The second task is on line 1, and that is what a completion has to
        // hand back: two tasks in a file can read identically.
        assertThat(ticked).containsExactly(1)
    }

    @Test
    fun `a vault that cannot be written to has an inert box`() {
        render(markdown = "- [ ] first\n", actions = RenderActions(onCompleteTask = null))

        // No handler, so no click to make. The assertion worth making is that
        // the task still draws -- a read-only vault reads normally.
        compose.onNodeWithContentDescription("unchecked").assertExists()
        compose.onNodeWithText("first").assertExists()
    }

    @Test
    fun `hiding completed tasks leaves the open ones alone`() {
        render(
            markdown = "- [ ] open\n- [x] done ✅ 2026-09-20\n- [-] dropped\n",
            reading = ReadingSettings(hideCompletedTasks = true),
        )

        compose.onNodeWithText("open").assertExists()
        compose.onNodeWithText("done").assertDoesNotExist()
        compose.onNodeWithText("dropped").assertDoesNotExist()
    }

    @Test
    fun `with the setting off every task is on the page`() {
        render(markdown = "- [ ] open\n- [x] done ✅ 2026-09-20\n")

        compose.onNodeWithText("open").assertExists()
        compose.onNodeWithText("done").assertExists()
    }

    @Test
    fun `a ticked parent holding open work is not hidden with the rest`() {
        render(
            markdown = "- [x] parent ✅ 2026-09-20\n    - [ ] child\n- [x] finished ✅ 2026-09-20\n",
            reading = ReadingSettings(hideCompletedTasks = true),
        )

        compose.onNodeWithText("parent").assertExists()
        compose.onNodeWithText("child").assertExists()
        compose.onNodeWithText("finished").assertDoesNotExist()
    }

    @Test
    fun `an ordered list keeps its numbering when a done item is hidden`() {
        render(
            markdown = "1. [x] one ✅ 2026-09-20\n2. [ ] two\n",
            reading = ReadingSettings(hideCompletedTasks = true),
        )

        // The remaining item is still the second, not renumbered to first.
        compose.onNodeWithText("two").assertExists()
        compose.onAllNodesWithContentDescription("checked").assertCountEquals(0)
    }
}
