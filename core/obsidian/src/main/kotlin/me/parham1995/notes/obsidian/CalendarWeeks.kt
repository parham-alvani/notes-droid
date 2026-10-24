package me.parham1995.notes.obsidian

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * One occurrence of an event, the way the phone's calendar provider expands
 * it: a recurring event is one of these per time it happens, so nothing here
 * reads a repeat rule.
 *
 * [begin] and [end] are epoch millis. For an all-day event they are UTC
 * midnights, whatever the phone's zone, which is how the provider stores them.
 */
data class Occurrence(
    /** The iCalendar UID, when the provider has one. */
    val uid: String?,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val title: String?,
    val location: String?,
    /** The calendar's name, which for a Google account is usually its address. */
    val calendar: String,
    /** Its calendar copies only the time: the title and the place stay on the phone. */
    val busyOnly: Boolean = false,
    /** The event's description, often HTML when Google wrote it. */
    val description: String? = null,
    val organizer: String? = null,
    /** Everyone invited, by name where the provider has one and by address otherwise. */
    val attendees: List<String> = emptyList(),
)

/**
 * The phone's calendars as markdown: one generated note per ISO week,
 * `Calendar/2026-W43.md`, Monday to Sunday.
 *
 * A week rather than a day because each changed file is one commit, and a
 * sync usually changes this week and the next, not fourteen separate days.
 *
 * The notes are written whole every time and never edited in place, so they
 * hold nothing that changes on its own -- no "generated at" line. A note that
 * comes out the same is not written, and a sync with no calendar changes
 * makes no commit.
 */
object CalendarWeeks {
    const val DEFAULT_FOLDER = "Calendar"

    /** Weeks before this one that are still written; older notes are left as they were. */
    const val WEEKS_BEFORE = 1

    /** Weeks after this one that are written. */
    const val WEEKS_AFTER = 8

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val WHITESPACE = Regex("""\s+""")
    private val TAG = Regex("""<[^>]*>""")
    private val BREAK = Regex("""(?i)<br\s*/?>|</p>|</div>|</li>""")
    private val URL = Regex("""https?://[^\s<>"')\]]+""")

    /** Hosts a meeting is joined on, so the link worth naming is the call, not a document. */
    private val CALL_HOSTS =
        listOf("meet.google.com", "zoom.us", "teams.microsoft.com", "teams.live.com", "whereby.com", "calendly.com")

    /** How much of a description a week note keeps: enough to know what the meeting is for. */
    const val NOTES_LIMIT = 300

    /** How many people a line names before it counts the rest. */
    const val PEOPLE_LIMIT = 8

    /** The Mondays of the weeks written around [today]. */
    fun weeks(
        today: LocalDate,
        before: Int = WEEKS_BEFORE,
        after: Int = WEEKS_AFTER,
    ): List<LocalDate> {
        val monday = mondayOf(today)
        return (-before..after).map { monday.plusWeeks(it.toLong()) }
    }

    fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * The span to ask the provider for, in epoch millis. A day wider on each
     * side than [weeks] covers, because an all-day event is stored at UTC
     * midnight and the phone's own midnight can be hours away from it.
     */
    fun span(
        weeks: List<LocalDate>,
        zone: ZoneId,
    ): LongRange {
        val from =
            weeks
                .first()
                .minusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        val to =
            weeks
                .last()
                .plusDays(8)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        return from until to
    }

    /** `Calendar/2026-W43.md`: the ISO week, in the year that week belongs to. */
    fun pathFor(
        folder: String,
        monday: LocalDate,
    ): String {
        val year = monday.get(IsoFields.WEEK_BASED_YEAR)
        val week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val name = "%d-W%02d.md".format(Locale.ROOT, year, week)
        val dir = folder.trim().trim('/')
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    /** Every week note around [today], by path. */
    fun export(
        folder: String,
        today: LocalDate,
        occurrences: List<Occurrence>,
        zone: ZoneId,
    ): Map<String, String> {
        val events = distinct(occurrences)
        return weeks(today).associate { monday -> pathFor(folder, monday) to render(monday, events, zone) }
    }

    /** The note for the week starting [monday]. */
    fun render(
        monday: LocalDate,
        occurrences: List<Occurrence>,
        zone: ZoneId,
    ): String {
        val events = distinct(occurrences)
        val name = pathFor("", monday).removeSuffix(".md")
        return buildString {
            append("# ").append(name).append("\n\n")
            append("Generated by Daftar from the calendars on the phone. ")
            append("Times are ").append(zone.id).append(". Edits to this note are written over.\n")
            for (offset in 0L until 7L) {
                val day = monday.plusDays(offset)
                append("\n## ").append(day.format(DAY)).append("\n\n")
                val lines = events.filter { it.isOn(day, zone) }.sortedWith(ORDER).map { it.line(day, zone) }
                if (lines.isEmpty()) {
                    append("No events.\n")
                } else {
                    lines.forEach { append(it) }
                }
            }
        }
    }

    /**
     * One entry per meeting. Two companies that invite you to the same
     * meeting give two occurrences with one UID and one start; they become one
     * entry that names both calendars. If either calendar is busy-only, the
     * entry is too: the stricter calendar wins.
     */
    fun distinct(occurrences: List<Occurrence>): List<Occurrence> {
        val (keyed, loose) = occurrences.partition { !it.uid.isNullOrBlank() }
        val merged =
            keyed.groupBy { it.uid to it.begin }.values.map { unordered ->
                // The provider gives the copies in no set order, and the one
                // that names the line has to be the same every sync, or the
                // note changes -- and commits -- with nothing changed.
                val same = unordered.sortedBy { it.calendar }
                val first = same.first()
                first.copy(
                    calendar = same.map { it.calendar }.distinct().joinToString(", "),
                    busyOnly = same.any { it.busyOnly },
                )
            }
        return merged + loose
    }

    private val ORDER = compareBy<Occurrence>({ !it.allDay }, { it.begin }, { it.title.orEmpty() })

    private fun Occurrence.isOn(
        day: LocalDate,
        zone: ZoneId,
    ): Boolean =
        if (allDay) {
            // Dates, not instants: the provider keeps them at UTC midnight, and
            // the end is the day after the last one.
            val first = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val after = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
            !day.isBefore(first) && (day.isBefore(after) || (day == first && !after.isAfter(first)))
        } else {
            Instant.ofEpochMilli(begin).atZone(zone).toLocalDate() == day
        }

    private fun Occurrence.line(
        day: LocalDate,
        zone: ZoneId,
    ): String {
        val what =
            if (busyOnly) {
                "Busy"
            } else {
                listOfNotNull(clean(title) ?: "(no title)", clean(location)).joinToString(" · ")
            }
        val `when` =
            if (allDay) {
                "All day"
            } else {
                val start = Instant.ofEpochMilli(begin).atZone(zone)
                val finish = Instant.ofEpochMilli(end).atZone(zone)
                val endText =
                    if (finish.toLocalDate() == day) {
                        finish.format(TIME)
                    } else {
                        "${finish.format(SHORT_DAY)} ${finish.format(TIME)}"
                    }
                "${start.format(TIME)}–$endText"
            }
        return buildString {
            append("- ")
                .append(`when`)
                .append(' ')
                .append(what)
                .append(" (")
                .append(calendar)
                .append(")\n")
            if (!busyOnly) details().forEach { append("  - ").append(it).append('\n') }
        }
    }

    /**
     * The lines under a meeting: how to join it, who is in it, and what it is
     * for -- what a person, or anything else reading the vault, needs to plan
     * around it. Each is one line, in a set order, and only when it says
     * something.
     */
    private fun Occurrence.details(): List<String> {
        val text = plain(description)
        val link = callLink(listOfNotNull(location, description))
        val people =
            attendees
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        val with =
            people.take(PEOPLE_LIMIT).joinToString(", ") +
                (if (people.size > PEOPLE_LIMIT) ", +${people.size - PEOPLE_LIMIT} more" else "")
        return listOfNotNull(
            link?.let { "Join: $it" },
            clean(organizer)?.let { "Organizer: $it" },
            with.takeIf { it.isNotEmpty() }?.let { "With: ${clean(it)}" },
            text?.let { "Notes: ${clean(it.ellipsized(NOTES_LIMIT))}" },
        )
    }

    /** The first link to a call, out of the location and then the description. */
    internal fun callLink(texts: List<String>): String? =
        texts
            .asSequence()
            .flatMap { URL.findAll(it).map { m -> m.value.trimEnd('.', ',', ';') } }
            .firstOrNull { url ->
                CALL_HOSTS.any { host -> url.substringAfter("://").substringBefore('/').endsWith(host) }
            }

    /** A description as plain text: tags gone, the common entities read, blank when it said nothing. */
    internal fun plain(html: String?): String? =
        html
            ?.replace(BREAK, " ")
            ?.replace(TAG, "")
            ?.replace("&nbsp;", " ")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")
            ?.replace("&amp;", "&")
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun String.ellipsized(limit: Int): String = if (length <= limit) this else take(limit).trimEnd() + "…"

    /**
     * One line of plain text. A title can hold a line break, which would end
     * the list item, and a `#`, which Obsidian would read as a tag and put in
     * the tag list of the whole vault.
     */
    private fun clean(text: String?): String? =
        text
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.replace("#", "\\#")
            ?.takeIf { it.isNotEmpty() }
}
