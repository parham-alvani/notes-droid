package me.parham1995.notes.data.calendar

import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncLog
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.obsidian.CalendarWeeks
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the chosen calendars into a vault as week notes ([CalendarWeeks]),
 * so that everything about the day is in the vault: the meetings next to the
 * tasks, and readable by whatever else reads the repository.
 *
 * Runs at the start of every sync, before the sync takes the vault: each week
 * that changed is a [VaultWriteRepository.writeGenerated], queued and sent the
 * way every edit is. A week that reads the same is no commit.
 */
@Singleton
class CalendarExport
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val calendars: PhoneCalendars,
        private val writes: VaultWriteRepository,
        private val log: SyncLog,
    ) {
        /** How many week notes changed. Zero when the export is off or not permitted. */
        suspend fun run(
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Int {
            val current = settings.current().calendar
            if (!current.exports) return 0
            if (!calendars.permitted()) {
                log.warn("calendar export is on, but calendar access is not granted")
                return 0
            }
            val weeks = CalendarWeeks.weeks(today)
            val events = calendars.events(current.calendars, current.busyOnly, CalendarWeeks.span(weeks, zone))
            val notes = CalendarWeeks.export(CalendarWeeks.DEFAULT_FOLDER, today, events.map { it.occurrence }, zone)
            var changed = 0
            for ((path, text) in notes) {
                val week = path.substringAfterLast('/').removeSuffix(".md")
                when (
                    val result =
                        writes.writeGenerated(
                            current.exportVaultId,
                            path,
                            text,
                            "docs(calendar): update $week",
                        )
                ) {
                    WriteResult.Pushed, is WriteResult.Queued -> changed++
                    WriteResult.Unchanged -> Unit
                    is WriteResult.Refused -> {
                        // Every week would say the same; once is enough.
                        log.warn("calendar export: ${result.why}")
                        return changed
                    }
                }
            }
            if (changed > 0) log.info("calendar export: $changed week note(s) changed")
            return changed
        }
    }
