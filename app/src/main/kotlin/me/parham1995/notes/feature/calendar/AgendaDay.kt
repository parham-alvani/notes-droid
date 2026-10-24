package me.parham1995.notes.feature.calendar

import me.parham1995.notes.data.calendar.PhoneEvent
import me.parham1995.notes.data.database.TaskRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** One day of the calendar screen: its meetings, then the tasks filed under it. */
data class AgendaDay(
    val date: LocalDate,
    val events: List<PhoneEvent>,
    val tasks: List<TaskRow>,
) {
    val isEmpty: Boolean get() = events.isEmpty() && tasks.isEmpty()
}

/**
 * The days from [today] on, each with what happens on it.
 *
 * An all-day event is on every date it covers, read in UTC because that is
 * how the provider keeps it; a timed one is on the day it starts, in [zone].
 * A task is on the day it is filed under -- due, else scheduled, the same
 * date the task list sorts by -- so a task moved there shows there.
 */
internal fun agenda(
    today: LocalDate,
    days: Int,
    events: List<PhoneEvent>,
    tasks: List<TaskRow>,
    zone: ZoneId,
): List<AgendaDay> {
    val byDay = tasks.groupBy { it.actionableOn }
    return (0 until days).map { offset ->
        val date = today.plusDays(offset.toLong())
        AgendaDay(
            date = date,
            events =
                events
                    .filter { it.isOn(date, zone) }
                    .sortedWith(compareBy({ !it.occurrence.allDay }, { it.occurrence.begin })),
            tasks = byDay[date.toString()].orEmpty(),
        )
    }
}

private fun PhoneEvent.isOn(
    date: LocalDate,
    zone: ZoneId,
): Boolean {
    val event = occurrence
    if (!event.allDay) return Instant.ofEpochMilli(event.begin).atZone(zone).toLocalDate() == date
    val first = Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).toLocalDate()
    val after = Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate()
    return !date.isBefore(first) && (date.isBefore(after) || (date == first && !after.isAfter(first)))
}
