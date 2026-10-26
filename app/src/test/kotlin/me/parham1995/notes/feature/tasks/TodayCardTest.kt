package me.parham1995.notes.feature.tasks

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.TaskBucket
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
 * The day at the top of the task list: its meetings with the one on now
 * marked, what is due in numbers, and what it says when there is no week note.
 *
 * Every fixture here is made up. The repository is public; the vault is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class TodayCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 26)

    private fun at(
        hour: Int,
        date: LocalDate = today,
    ) = date
        .atTime(hour, 0)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli()

    private fun row(
        id: Long,
        state: String = "UNCHECKED",
    ) = TaskRow(
        id = id,
        noteId = 1,
        text = "task $id",
        state = state,
        section = "",
        blockIndex = 0,
        line = 0,
        actionableOn = null,
        recurring = null,
        notePath = "Tasks.md",
        noteTitle = "Tasks",
        vaultId = 1,
    )

    @Test
    fun `the counts come from the groups below`() {
        val counts =
            TodayCounts.of(
                listOf(
                    TaskGroup(TaskBucket.OVERDUE, listOf(row(1), row(2, "IN_PROGRESS"))),
                    TaskGroup(TaskBucket.TODAY, listOf(row(3))),
                    TaskGroup(TaskBucket.TOMORROW, listOf(row(4))),
                    TaskGroup(TaskBucket.THIS_WEEK, listOf(row(5), row(6))),
                    TaskGroup(TaskBucket.LATER, listOf(row(7))),
                ),
            )
        assertThat(counts).isEqualTo(TodayCounts(overdue = 2, due = 1, inProgress = 1, coming = 3))
    }

    @Test
    fun `the day's meetings are listed, with how tomorrow starts`() {
        val calendar =
            TodayCalendar(
                today =
                    WeekDay(
                        today,
                        free = "11:00–18:00",
                        meetings =
                            listOf(
                                WeekMeeting(today, at(9), at(10), "Standup", overlaps = "09:30 Review"),
                                WeekMeeting(today, title = "Holiday"),
                            ),
                    ),
                tomorrowStarts =
                    WeekMeeting(
                        today.plusDays(1),
                        at(8, today.plusDays(1)),
                        at(9, today.plusDays(1)),
                        "Early call",
                    ),
            )
        compose.setContent {
            TodayCard(calendar, TodayCounts(overdue = 2, due = 1), today = today, zone = ZoneOffset.UTC)
        }

        compose.onNodeWithText("Monday 26 October").assertExists()
        compose.onNodeWithText("2 meetings").assertExists()
        compose.onNodeWithText("free 11:00–18:00").assertExists()
        compose.onNodeWithText("2 overdue").assertExists()
        compose.onNodeWithText("Standup").assertExists()
        compose.onNodeWithText("Overlaps 09:30 Review").assertExists()
        compose.onNodeWithText("All day").assertExists()
        compose.onNodeWithText("Tomorrow starts at", substring = true).assertExists()
        compose.onNodeWithText("Early call", substring = true).assertExists()
    }

    @Test
    fun `no week note is said, with where it would be`() {
        compose.setContent { TodayCard(TodayCalendar(), TodayCounts(), today = today, zone = ZoneOffset.UTC) }
        compose.onNodeWithText("There is no week note for today in Calendar/", substring = true).assertExists()
        compose.onNodeWithText("0 meetings").assertExists()
    }
}
