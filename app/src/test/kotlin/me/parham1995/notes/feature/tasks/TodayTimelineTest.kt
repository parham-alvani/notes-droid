package me.parham1995.notes.feature.tasks

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.calendar.TodayCalendar
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.obsidian.WeekDay
import me.parham1995.notes.obsidian.WeekMeeting
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The timeline under Today: a task's box ticks and opens the task it was
 * drawn for, and not its neighbour -- for a write, the wrong row is worse
 * than none.
 *
 * Every fixture here is made up. The repository is public; the vault is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class TodayTimelineTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 14)

    private fun at(
        hour: Int,
        minute: Int = 0,
    ) = today
        .atTime(hour, minute)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli()

    private fun row(
        id: Long,
        text: String,
        day: LocalDate? = today,
    ) = TaskRow(
        id = id,
        noteId = id * 10,
        text = text,
        state = "UNCHECKED",
        section = "Errands",
        blockIndex = 0,
        line = id.toInt(),
        actionableOn = day?.toString(),
        recurring = null,
        notePath = "Tasks/Home.md",
        noteTitle = "Home",
        vaultId = 1,
    )

    private val calendar =
        TodayCalendar(
            today =
                WeekDay(
                    today,
                    meetings =
                        listOf(
                            WeekMeeting(today, at(9), at(10), "Standup"),
                            WeekMeeting(today, title = "Library day"),
                        ),
                ),
        )

    private val tasks =
        listOf(
            row(1, "10:30 Call the bank"),
            row(2, "14:00-15:30 Write the review"),
            row(3, "Water the plants"),
            row(4, "16:00 Not today", day = today.plusDays(1)),
        )

    private fun show(
        canWrite: Boolean = true,
        calendar: TodayCalendar = this.calendar,
        tasks: List<TaskRow> = this.tasks,
        ticked: MutableList<Long> = mutableListOf(),
        opened: MutableList<Long> = mutableListOf(),
    ) {
        compose.setContent {
            TodayTimeline(
                calendar = calendar,
                tasks = tasks,
                canWrite = canWrite,
                onComplete = { ticked += it.id },
                onOpen = { opened += it.id },
                today = today,
                zone = ZoneOffset.UTC,
                clock = { at(11) },
            )
        }
    }

    @Test
    fun `ticking a task's box hands back that task`() {
        val ticked = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        show(ticked = ticked, opened = opened)

        compose.onNodeWithTag("timeline-tick-2").performClick()

        assertThat(ticked).containsExactly(2L)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `tapping a task's box opens that task`() {
        val ticked = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        show(ticked = ticked, opened = opened)

        compose.onNodeWithText("Write the review").performClick()

        assertThat(opened).containsExactly(2L)
        assertThat(ticked).isEmpty()
    }

    @Test
    fun `the day's meetings, timed tasks, all-day events, free time and now are drawn`() {
        show()

        compose.onNodeWithText("Standup", substring = true).assertExists()
        compose.onNodeWithText("Library day").assertExists()
        compose.onNodeWithText("Call the bank", substring = true).assertExists()
        // Untimed, and another day's: on the list, not on the timeline.
        compose.onNodeWithText("Water the plants", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Not today", substring = true).assertDoesNotExist()
        // 11:00 to 14:00, between the call and the review.
        compose.onNodeWithText("free 3h").assertExists()
        compose.onNodeWithTag("timeline-now").assertExists()
    }

    @Test
    fun `a vault that cannot be written to offers no box to tick`() {
        show(canWrite = false)
        compose.onNodeWithTag("timeline-tick-2").assertDoesNotExist()
    }

    @Test
    fun `no week note and no timed task is no timeline`() {
        show(calendar = TodayCalendar(), tasks = listOf(row(3, "Water the plants")))
        compose.onNodeWithTag("timeline").assertDoesNotExist()
    }
}
