package me.parham1995.notes.obsidian

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A time at the start of a task's text, as the Obsidian Day Planner plugin
 * writes it: `09:30 Call Sara`, `14:00-15:30 Write the review`.
 *
 * [start] and [end] are minutes from midnight on the viewer's wall clock; a
 * task has no time zone. [text] is the rest of the text, trimmed, which is
 * what the timeline shows -- everywhere else the text is left as it is.
 *
 * `today-timeline.md` §1 in notes-linux's `docs/specs/`, shared with Daftar
 * for Linux and the Mac.
 */
data class TaskTime(
    val start: Int,
    val end: Int,
    val text: String,
) {
    companion object {
        /** Without an end, or with one that is not after the start. */
        const val DEFAULT_MINUTES = 30

        private const val DAY = 1440
        private const val HOURS = 24
        private const val MINUTES = 60

        // ASCII digits only; the time, or the range, ends at a space or at the end.
        private val TIME = Regex("""^([0-9]{1,2}):([0-9]{2})(?: ?[-–] ?([0-9]{1,2}):([0-9]{2}))?(?= |$)""")

        /** The time [text] starts with, or null when it starts with none. */
        fun parse(text: String): TaskTime? {
            val match = TIME.find(text) ?: return null
            val g = match.groupValues
            val start = minutes(g[1], g[2]) ?: return null
            val end =
                if (g[3].isEmpty()) {
                    null
                } else {
                    // A range whose end is no time is no range, and what follows the start is no space.
                    minutes(g[3], g[4]) ?: return null
                }
            val until = end?.takeIf { it > start } ?: minOf(start + DEFAULT_MINUTES, DAY)
            return TaskTime(start, until, text.substring(match.range.last + 1).trim())
        }

        private fun minutes(
            hour: String,
            minute: String,
        ): Int? {
            val h = hour.toInt()
            val m = minute.toInt()
            return if (h < HOURS && m < MINUTES) h * MINUTES + m else null
        }
    }
}

/**
 * A task as the timeline needs it: the key it is handed back by, its text as
 * the task lists show it, its day (its due date, else its scheduled date, as
 * Today groups tasks) and whether its status's type is open.
 */
data class TimelineTask(
    val key: Long,
    val text: String,
    /** `2026-11-14`, or null for an undated task. */
    val day: String?,
    val open: Boolean = true,
)

/** One box on the timeline: a meeting or a task. */
data class TimelineItem(
    /** Minutes from the day's midnight on the wall clock, cut to the day. */
    val start: Int,
    val end: Int,
    /** What the box says: the meeting's title, or the task's text without its time. */
    val title: String,
    /** The meeting, or null for a task. */
    val meeting: WeekMeeting? = null,
    /** The task, or null for a meeting. */
    val task: TimelineTask? = null,
    /** The column it is drawn in, from 0. */
    val lane: Int = 0,
    /** How many columns its cluster uses. */
    val lanes: Int = 1,
    /** Ended by now. */
    val past: Boolean = false,
    /** Started by now and not yet ended. */
    val current: Boolean = false,
)

/** A stretch of the working day that nothing takes, in minutes from midnight. */
data class FreeTime(
    val start: Int,
    val end: Int,
) {
    val minutes: Int get() = end - start
}

/** One day on one axis of hours: `today-timeline.md` §2–§4. */
data class DayTimeline(
    val date: LocalDate,
    /** By start, then the longer first, then meetings before tasks. */
    val items: List<TimelineItem>,
    /** The day's own all-day events, in their order in the note. */
    val allDay: List<WeekMeeting>,
    /** The first and last minute of the axis, each on the hour. */
    val axisStart: Int,
    val axisEnd: Int,
    val free: List<FreeTime>,
    /** The minute of the wall clock when the day is today; null on another day. */
    val now: Int?,
)

/**
 * The day's meetings and timed tasks on one axis of hours, with the free
 * time between them and a line at now -- `today-timeline.md` in notes-linux's
 * `docs/specs/`, shared with Daftar for Linux and the Mac.
 */
object Timeline {
    private const val DAY = 1440
    private const val HOUR = 60

    /** A gap shorter than this is not free time, as in the week notes. */
    const val MIN_FREE = 15

    /**
     * The timeline of [date] in [zone].
     *
     * [meetings] may come from any days: the ones dated [date] and the day
     * before are read, the rest left out. [now] is epoch millis, or null for
     * no clock at all.
     */
    fun build(
        date: LocalDate,
        meetings: List<WeekMeeting>,
        tasks: List<TimelineTask>,
        zone: ZoneId,
        now: Long? = null,
        hours: WorkingHours = WorkingHours(),
    ): DayTimeline {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd =
            date
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        val listed = meetings.filter { it.date == date || it.date == date.minusDays(1) }

        val timed =
            listed.mapNotNull { meeting ->
                val start = meeting.start ?: return@mapNotNull null
                val end = meeting.end ?: return@mapNotNull null
                if (end <= dayStart || start >= dayEnd) return@mapNotNull null
                val from = if (start <= dayStart) 0 else minuteOf(start, zone)
                val until = if (end >= dayEnd) DAY else minuteOf(end, zone)
                if (until <= from) return@mapNotNull null
                TimelineItem(from, until, meeting.title, meeting = meeting)
            }
        val day = date.toString()
        val planned =
            tasks
                .filter { it.open && it.day == day }
                .mapNotNull { task ->
                    TaskTime.parse(task.text)?.let { TimelineItem(it.start, it.end, it.text, task = task) }
                }

        val ordered =
            (timed + planned)
                .withIndex()
                .sortedWith(
                    compareBy<IndexedValue<TimelineItem>> { it.value.start }
                        .thenByDescending { it.value.end - it.value.start }
                        .thenBy { if (it.value.meeting != null) 0 else 1 }
                        .thenBy { it.index },
                ).map { it.value }

        val minute =
            now?.takeIf { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == date }?.let { minuteOf(it, zone) }
        val items =
            lanes(ordered).map { item ->
                if (minute == null) {
                    item
                } else {
                    item.copy(past = item.end <= minute, current = item.start <= minute && minute < item.end)
                }
            }

        val workStart = hours.start.hour * HOUR + hours.start.minute
        val workEnd = hours.end.hour * HOUR + hours.end.minute
        val earliest = listOfNotNull(workStart, items.minOfOrNull { it.start }, minute).min()
        val latest = listOfNotNull(workEnd, items.maxOfOrNull { it.end }, minute).max()

        return DayTimeline(
            date = date,
            items = items,
            allDay = listed.filter { it.allDay && it.date == date },
            axisStart = (earliest / HOUR * HOUR).coerceIn(0, DAY),
            axisEnd = ((latest + HOUR - 1) / HOUR * HOUR).coerceIn(0, DAY),
            free = free(items, workStart, workEnd),
            now = minute,
        )
    }

    /** A length as the free time is labelled: `45m`, `2h`, `1h 30m`. */
    fun duration(minutes: Int): String {
        val h = minutes / HOUR
        val m = minutes % HOUR
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** The minute of the wall clock at [millis], from that day's midnight. */
    private fun minuteOf(
        millis: Long,
        zone: ZoneId,
    ): Int {
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        return time.hour * HOUR + time.minute
    }

    /**
     * Side by side where they share time: a cluster is a run in which each
     * starts before the latest end so far, and each item takes the first lane
     * free by its start.
     */
    private fun lanes(ordered: List<TimelineItem>): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()
        val cluster = mutableListOf<TimelineItem>()
        val laneEnds = mutableListOf<Int>()
        var clusterEnd = Int.MIN_VALUE

        fun close() {
            cluster.forEach { out += it.copy(lanes = laneEnds.size) }
            cluster.clear()
            laneEnds.clear()
        }

        for (item in ordered) {
            if (cluster.isNotEmpty() && item.start >= clusterEnd) close()
            val lane = laneEnds.indexOfFirst { it <= item.start }.takeIf { it >= 0 } ?: laneEnds.size
            if (lane == laneEnds.size) laneEnds += item.end else laneEnds[lane] = item.end
            cluster += item.copy(lane = lane)
            clusterEnd = if (cluster.size == 1) item.end else maxOf(clusterEnd, item.end)
        }
        close()
        return out
    }

    /** The working day's stretches of [MIN_FREE] minutes or more that no item takes. */
    private fun free(
        items: List<TimelineItem>,
        workStart: Int,
        workEnd: Int,
    ): List<FreeTime> {
        val taken =
            items
                .filter { it.start < workEnd && it.end > workStart }
                .map { maxOf(it.start, workStart) to minOf(it.end, workEnd) }
                .sortedBy { it.first }
        val gaps = mutableListOf<FreeTime>()
        var from = workStart
        for ((start, end) in taken) {
            if (start > from) gaps += FreeTime(from, start)
            from = maxOf(from, end)
        }
        if (workEnd > from) gaps += FreeTime(from, workEnd)
        return gaps.filter { it.minutes >= MIN_FREE }
    }
}
