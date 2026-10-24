package me.parham1995.notes.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.calendar.PhoneCalendars
import me.parham1995.notes.data.calendar.PhoneEvent
import me.parham1995.notes.obsidian.CalendarWeeks
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class CalendarUiState(
    val loading: Boolean = true,
    val permitted: Boolean = false,
    /** No calendar chosen yet; the screen points at the settings. */
    val unchosen: Boolean = false,
    val days: List<AgendaDay> = emptyList(),
)

/**
 * The next two weeks: the chosen calendars, read live from the phone, and the
 * open tasks of the vault being read. Live rather than from the week notes, so
 * a meeting added a minute ago is here before any sync has run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel
    @Inject
    constructor(
        private val calendars: PhoneCalendars,
        settings: SettingsStore,
        repository: VaultRepository,
    ) : ViewModel() {
        /** Bumped after the permission dialog, which no content observer hears about. */
        private val asked = MutableStateFlow(0)

        private val events =
            combine(settings.settings.map { it.calendar.calendars }, asked) { chosen, _ -> chosen }
                .flatMapLatest { chosen -> calendars.changes().map { chosen } }
                .mapLatest { chosen -> chosen to load(chosen) }

        val state: StateFlow<CalendarUiState> =
            combine(events, repository.openTasks()) { (chosen, found), tasks ->
                CalendarUiState(
                    loading = false,
                    permitted = calendars.permitted(),
                    unchosen = chosen.isEmpty(),
                    days = agenda(LocalDate.now(), DAYS, found, tasks, ZoneId.systemDefault()),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), CalendarUiState())

        fun permissionAnswered() {
            asked.value++
        }

        private suspend fun load(chosen: Set<Long>): List<PhoneEvent> {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            // The week notes' span, from the start of this week: wide enough
            // for every day shown, with a day to spare for all-day events.
            val span = CalendarWeeks.span(listOf(CalendarWeeks.mondayOf(today), today.plusDays(DAYS.toLong())), zone)
            return calendars.events(chosen, emptySet(), span)
        }

        private companion object {
            const val DAYS = 14
            const val STOP_MS = 5_000L
        }
    }
