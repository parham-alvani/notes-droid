package me.parham1995.notes.data.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import me.parham1995.notes.data.CalendarSettings
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultFileStore
import me.parham1995.notes.data.database.SyncStateDao
import me.parham1995.notes.obsidian.CalendarWeeks
import me.parham1995.notes.obsidian.WeekDay
import me.parham1995.notes.obsidian.WeekMeeting
import me.parham1995.notes.obsidian.WeekNotes
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Today's meetings and free time, and how tomorrow starts. */
data class TodayCalendar(
    /** Null when there is no week note for today and no calendar to read. */
    val today: WeekDay? = null,
    /** Tomorrow's first meeting with a time. */
    val tomorrowStarts: WeekMeeting? = null,
    /** Read from the phone's calendars rather than from a week note. */
    val live: Boolean = false,
)

/**
 * Where today's meetings come from: the week notes in `Calendar/`, which this
 * app writes from the phone's calendars and the Mac reads.
 *
 * When calendars are chosen and readable here, the note is rendered from them
 * there and then rather than read from disk -- the same text the next export
 * will write, so a meeting added a minute ago is in it before any sync. When
 * they are not -- no permission, nothing chosen -- the note the vault holds is
 * read, so the day is still there.
 */
@Singleton
class TodayCalendarSource
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val calendars: PhoneCalendars,
        private val files: VaultFileStore,
        private val syncState: SyncStateDao,
    ) {
        /** Today in [activeVault], again whenever a calendar, a setting or a sync changes it. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(activeVault: Flow<Long>): Flow<TodayCalendar> =
            combine(
                settings.settings.map { it.calendar }.distinctUntilChanged(),
                activeVault,
                calendars.changes(),
                syncState.observe().map { it?.lastSyncAt }.distinctUntilChanged(),
            ) { calendar, vault, _, _ -> calendar to vault }
                .mapLatest { (calendar, vault) -> load(calendar, vault) }
                .flowOn(Dispatchers.IO)

        suspend fun load(
            calendar: CalendarSettings,
            activeVault: Long,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): TodayCalendar {
            val tomorrow = today.plusDays(1)
            val mondays = listOf(CalendarWeeks.mondayOf(today), CalendarWeeks.mondayOf(tomorrow)).distinct()
            val live = calendar.calendars.isNotEmpty() && calendars.permitted()
            val weeks =
                if (live) {
                    val events =
                        calendars
                            .events(calendar.calendars, calendar.busyOnly, CalendarWeeks.span(mondays, zone))
                            .map { it.occurrence }
                    mondays.map { monday ->
                        val path = CalendarWeeks.pathFor(CalendarWeeks.DEFAULT_FOLDER, monday)
                        WeekNotes.parse(CalendarWeeks.render(monday, events, zone, calendar.hours), path, zone)
                    }
                } else {
                    // The vault the notes are written to, else the one being read.
                    val vault = calendar.exportVaultId.takeIf { it != 0L } ?: activeVault
                    mondays.map { monday ->
                        val path = CalendarWeeks.pathFor(CalendarWeeks.DEFAULT_FOLDER, monday)
                        files.readText(vault, path)?.let { WeekNotes.parse(it, path, zone) }.orEmpty()
                    }
                }
            val days = weeks.flatten()
            return TodayCalendar(
                today = days.firstOrNull { it.date == today },
                tomorrowStarts = days.firstOrNull { it.date == tomorrow }?.meetings?.firstOrNull { !it.allDay },
                live = live,
            )
        }
    }
