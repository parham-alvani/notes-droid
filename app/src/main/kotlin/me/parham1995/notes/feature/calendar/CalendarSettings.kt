package me.parham1995.notes.feature.calendar

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.CalendarSettings
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.calendar.PhoneCalendar
import me.parham1995.notes.data.calendar.PhoneCalendars
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.feature.sync.SectionCard
import javax.inject.Inject

data class CalendarSettingsState(
    val permitted: Boolean = false,
    val calendars: List<PhoneCalendar> = emptyList(),
    val settings: CalendarSettings = CalendarSettings(),
    /** The vaults the week notes can go to: the ones this phone can push to, with an author set. */
    val writable: List<VaultEntity> = emptyList(),
)

@HiltViewModel
class CalendarSettingsViewModel
    @Inject
    constructor(
        private val calendars: PhoneCalendars,
        private val store: SettingsStore,
        repository: VaultRepository,
        writes: VaultWriteRepository,
    ) : ViewModel() {
        private val phone = MutableStateFlow(false to emptyList<PhoneCalendar>())

        val state: StateFlow<CalendarSettingsState> =
            combine(
                phone,
                store.settings,
                repository.vaults(),
                writes.writable,
            ) { (permitted, found), settings, vaults, ids ->
                CalendarSettingsState(
                    permitted = permitted,
                    calendars = found.sortedWith(compareBy({ it.account }, { it.name })),
                    settings = settings.calendar,
                    writable = vaults.filter { it.id in ids },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), CalendarSettingsState())

        /** On every resume and after the permission dialog: both change outside the app. */
        fun recheck() =
            viewModelScope.launch {
                phone.value = calendars.permitted() to calendars.calendars()
            }

        fun setShown(
            id: Long,
            shown: Boolean,
        ) = viewModelScope.launch { store.setCalendarShown(id, shown) }

        fun setBusyOnly(
            id: Long,
            busyOnly: Boolean,
        ) = viewModelScope.launch { store.setCalendarBusyOnly(id, busyOnly) }

        fun setExportVault(vaultId: Long) = viewModelScope.launch { store.setCalendarExportVault(vaultId) }

        private companion object {
            const val STOP_MS = 5_000L
        }
    }

/** Which calendars the calendar screen shows, and where their week notes go. */
@Composable
internal fun CalendarCards(viewModel: CalendarSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.recheck() }
    LifecycleResumeEffect(Unit) {
        viewModel.recheck()
        onPauseOrDispose { }
    }

    SectionCard(stringResource(R.string.card_calendars)) {
        if (!state.permitted) {
            Text(stringResource(R.string.calendar_needs_access), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { permission.launch(Manifest.permission.READ_CALENDAR) }) {
                Text(stringResource(R.string.calendar_allow))
            }
            return@SectionCard
        }
        if (state.calendars.isEmpty()) {
            Text(
                stringResource(R.string.calendar_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.calendars.forEach { calendar ->
            val shown = calendar.id in state.settings.calendars
            Column {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        shown,
                        role = Role.Checkbox,
                    ) { viewModel.setShown(calendar.id, it) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = shown, onCheckedChange = null)
                    Box(Modifier.size(10.dp).background(Color(calendar.color), CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text(calendar.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            calendar.account,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (shown) {
                        FilterChip(
                            selected = calendar.id in state.settings.busyOnly,
                            onClick = { viewModel.setBusyOnly(calendar.id, calendar.id !in state.settings.busyOnly) },
                            label = { Text(stringResource(R.string.calendar_busy_only)) },
                        )
                    }
                }
            }
        }
    }

    SectionCard(stringResource(R.string.card_calendar_export)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.settings.exportVaultId == 0L,
                onClick = { viewModel.setExportVault(0L) },
                label = { Text(stringResource(R.string.calendar_export_off)) },
            )
            state.writable.forEach { vault ->
                FilterChip(
                    selected = state.settings.exportVaultId == vault.id,
                    onClick = { viewModel.setExportVault(vault.id) },
                    label = { Text(vault.label) },
                )
            }
        }
        if (state.writable.isEmpty()) {
            Text(
                stringResource(R.string.calendar_export_needs_write),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.help_calendar_export),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
