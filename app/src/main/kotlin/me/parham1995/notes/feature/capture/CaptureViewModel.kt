package me.parham1995.notes.feature.capture

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.data.database.VaultEntity
import javax.inject.Inject

data class CaptureUiState(
    /** Whether the capture can be written, with the reason when not. */
    val ready: Boolean = false,
    @param:StringRes val blocked: Int? = null,
    /** The vault [blocked] is about, for the reasons that name one. */
    val blockedVault: String = "",
    val destination: String = "",
    val saving: Boolean = false,
    /**
     * Why the last save was turned down. The dialog stays open with the text
     * in it, because closing it would throw away the thing being kept.
     */
    val refused: String? = null,
    /** Set once the capture is done; the screen closes on it. */
    @param:StringRes val finished: Int? = null,
)

/**
 * Whether a capture can land, and where.
 *
 * About the one vault the capture is written to -- the scratchpad's, or the one
 * being read when none is chosen -- and not about whether any vault at all can
 * be written. Asking the second question offered the field whenever some other
 * repository could take a push, then refused the save after the thought had
 * been typed.
 */
internal data class CaptureGate(
    val vault: VaultEntity?,
    @param:StringRes val blocked: Int?,
) {
    val ready: Boolean get() = blocked == null
}

/**
 * The vault a capture goes to, chosen the way [VaultWriteRepository.capture]
 * chooses it: the scratchpad's own vault, else the active one, else the first
 * enabled. Two answers to that question would be a gate that lets through what
 * the write then refuses.
 */
internal fun captureGate(
    settings: VaultSettings,
    vaults: List<VaultEntity>,
    writable: Set<Long>,
): CaptureGate {
    val targetId =
        settings.write.scratchpadVaultId.takeIf { it != 0L }
            ?: settings.activeVaultId.takeIf { it != 0L }
            ?: vaults.firstOrNull { it.enabled }?.id
    val target = vaults.firstOrNull { it.id == targetId }
    val blocked =
        when {
            !settings.write.hasAuthor -> R.string.capture_needs_author
            target == null -> R.string.capture_no_vault
            target.id !in writable -> R.string.capture_read_only
            else -> null
        }
    return CaptureGate(target, blocked)
}

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
        repository: VaultRepository,
        settings: SettingsStore,
    ) : ViewModel() {
        private val local = MutableStateFlow(CaptureUiState())

        val state: StateFlow<CaptureUiState> =
            combine(
                writes.writable,
                repository.vaults(),
                settings.settings,
                local,
            ) { writable, vaults, current, extra ->
                val gate = captureGate(current, vaults, writable)
                // The vault is named only when there is more than one it could
                // be, which is when it is worth reading.
                val named = gate.vault?.takeIf { vaults.size > 1 }
                extra.copy(
                    ready = gate.ready,
                    blocked = gate.blocked,
                    blockedVault = gate.vault?.label.orEmpty(),
                    destination = listOfNotNull(named?.label, current.write.scratchpadPath).joinToString(" / "),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CaptureUiState())

        fun save(text: String) =
            viewModelScope.launch {
                local.update { it.copy(saving = true, refused = null) }
                when (val result = writes.capture(text)) {
                    WriteResult.Pushed -> local.update { it.copy(saving = false, finished = R.string.capture_saved) }
                    is WriteResult.Queued ->
                        local.update { it.copy(saving = false, finished = R.string.capture_queued) }

                    WriteResult.Unchanged ->
                        local.update { it.copy(saving = false, finished = R.string.capture_nothing) }

                    is WriteResult.Refused -> local.update { it.copy(saving = false, refused = result.why) }
                }
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
