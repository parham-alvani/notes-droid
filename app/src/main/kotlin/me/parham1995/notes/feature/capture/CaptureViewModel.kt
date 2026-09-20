package me.parham1995.notes.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.WriteResult
import javax.inject.Inject

data class CaptureUiState(
    /** Whether anything can be written at all, with the reason when not. */
    val ready: Boolean = false,
    val blocked: String? = null,
    val destination: String = "",
    val saving: Boolean = false,
    /** Set once the capture is done; the screen closes on it. */
    val finished: String? = null,
)

/**
 * The quick capture: one field, one button, and the screen goes away.
 *
 * Kept apart from the rest of the app because this is what a share sheet and a
 * widget open, and both of those want the thing in front of them in under a
 * second -- not the reader with a dialog on top of it.
 */
@HiltViewModel
class CaptureViewModel
    @Inject
    constructor(
        private val writes: VaultWriteRepository,
        settings: SettingsStore,
    ) : ViewModel() {
        private val local = MutableStateFlow(CaptureUiState())

        val state: StateFlow<CaptureUiState> =
            combine(
                writes.writable,
                settings.settings.map { it.write },
                local,
            ) { writable, write, extra ->
                val ready = writable.isNotEmpty() && write.hasAuthor
                extra.copy(
                    ready = ready,
                    blocked =
                        when {
                            ready -> null
                            !write.hasAuthor -> "Set a name and email to commit as, in Settings."
                            else -> "No vault here can be written to. Check access in Settings."
                        },
                    destination = write.scratchpadPath,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CaptureUiState())

        fun save(text: String) =
            viewModelScope.launch {
                local.value = local.value.copy(saving = true)
                val said =
                    when (val result = writes.capture(text)) {
                        WriteResult.Pushed -> "Saved"
                        is WriteResult.Queued -> "Saved here - it goes up with the next sync"
                        WriteResult.Unchanged -> "Nothing to save"
                        is WriteResult.Refused -> result.why
                    }
                local.value = local.value.copy(saving = false, finished = said)
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
