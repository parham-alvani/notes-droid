package me.parham1995.notes.feature.calendar

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.calendar.PhoneEvent
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.obsidian.Occurrence
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The calendar screen's days, and the taps on them.
 *
 * The grouping is checked on its own; the list is checked for the failure this
 * app has shipped before -- a row that is drawn and reaches nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AgendaTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Europe/Madrid")

    /** A Saturday. */
    private val today = LocalDate.of(2026, 10, 24)

    private fun at(text: String): Long =
        LocalDateTime
            .parse(text)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun utcDay(date: String): Long =
        LocalDate
            .parse(date)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    private fun event(
        id: Long,
        title: String,
        begin: Long,
        end: Long,
        allDay: Boolean = false,
    ) = PhoneEvent(
        eventId = id,
        calendarId = 1,
        color = 0xFF1E6FD9.toInt(),
        occurrence = Occurrence("u$id", begin, end, allDay, title, null, "work@example.com"),
    )

    private fun task(
        id: Long,
        text: String,
        on: String?,
    ) = TaskRow(
        id = id,
        noteId = 40 + id,
        text = text,
        state = "UNCHECKED",
        section = "Home",
        blockIndex = 0,
        line = 3,
        actionableOn = on,
        recurring = null,
        notePath = "Tasks/Home.md",
        noteTitle = "Home",
        vaultId = 1,
    )

    @Test
    fun `each day holds its meetings, all-day first, and the tasks filed under it`() {
        val days =
            agenda(
                today,
                days = 3,
                events =
                    listOf(
                        event(1, "Standup", at("2026-10-25T09:00"), at("2026-10-25T09:15")),
                        event(2, "Trip", utcDay("2026-10-25"), utcDay("2026-10-27"), allDay = true),
                        event(3, "Old", at("2026-10-20T09:00"), at("2026-10-20T10:00")),
                    ),
                tasks = listOf(task(1, "pay rent", "2026-10-25"), task(2, "someday", null)),
                zone = zone,
            )
        assertThat(days.map { it.date.toString() }).containsExactly("2026-10-24", "2026-10-25", "2026-10-26").inOrder()
        assertThat(days[0].isEmpty).isTrue()
        assertThat(days[1].events.map { it.occurrence.title }).containsExactly("Trip", "Standup").inOrder()
        assertThat(days[1].tasks.map { it.text }).containsExactly("pay rent")
        assertThat(days[2].events.map { it.occurrence.title }).containsExactly("Trip")
    }

    @Test
    fun `a tap on a meeting opens that meeting, and a tap on a task opens its note`() {
        val opened = mutableListOf<Long>()
        val notes = mutableListOf<Long>()
        val days =
            agenda(
                today,
                days = 2,
                events =
                    listOf(
                        event(7, "Planning", at("2026-10-24T10:00"), at("2026-10-24T11:00")),
                        event(8, "Review", at("2026-10-24T12:00"), at("2026-10-24T13:00")),
                    ),
                tasks = listOf(task(2, "send the invoice", "2026-10-25")),
                zone = zone,
            )
        compose.setContent {
            Agenda(
                days = days,
                today = today,
                onOpenEvent = { opened += it.eventId },
                onOpenTask = { notes += it.noteId },
            )
        }
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("send the invoice").performClick()
        assertThat(opened).containsExactly(8L)
        assertThat(notes).containsExactly(42L)
    }
}
