package me.parham1995.notes.feature.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncRepository
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.SshKeyStore
import me.parham1995.notes.data.git.StoredKey
import javax.inject.Inject

/** How a repository's key last answered when it was tried. */
sealed interface KeyCheck {
    data object Untested : KeyCheck

    data object Running : KeyCheck

    data class Passed(
        val message: String,
    ) : KeyCheck

    data class Failed(
        val message: String,
    ) : KeyCheck
}

data class SshKeyRow(
    val vault: VaultEntity,
    val key: StoredKey?,
    val check: KeyCheck = KeyCheck.Untested,
) {
    val label: String get() = vault.label
    val remote: String get() = "${vault.owner}/${vault.repo}"
}

data class SshUiState(
    val repositories: List<SshKeyRow> = emptyList(),
    /**
     * Keys belonging to no configured repository -- what a removed repository
     * leaves behind. Worth surfacing: it is still a private key on the device.
     */
    val orphans: List<StoredKey> = emptyList(),
    val overPort443: Boolean = false,
    val busy: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class SshViewModel
    @Inject
    constructor(
        private val keys: SshKeyStore,
        private val repository: SyncRepository,
        private val settings: SettingsStore,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SshUiState())
        val state: StateFlow<SshUiState> = _state.asStateFlow()

        init {
            // Follows both the repositories and the key files. A repository
            // added elsewhere and a key generated here are different events,
            // and rebuilding only after the second left this list showing a
            // repository that had gone or missing one that had arrived.
            viewModelScope.launch {
                combine(repository.vaults(), keys.revision) { _, _ -> Unit }
                    .collect { refresh() }
            }
        }

        fun refresh() =
            viewModelScope.launch {
                val vaults = repository.vaults().first()
                val ssh = vaults.filter { SyncTransport.parse(it.transport) == SyncTransport.SSH }
                val stored = keys.stored()
                val claimed = vaults.map { keys.identity(it.id).name }.toSet()

                _state.value =
                    _state.value.copy(
                        repositories =
                            ssh.map { vault ->
                                val name = keys.identity(vault.id).name
                                SshKeyRow(
                                    vault = vault,
                                    key = stored.firstOrNull { it.fileName == name },
                                    // Kept across a refresh so a result does not
                                    // vanish the moment the list reloads.
                                    check =
                                        _state.value.repositories
                                            .firstOrNull { it.vault.id == vault.id }
                                            ?.check ?: KeyCheck.Untested,
                                )
                            },
                        orphans = stored.filterNot { it.fileName in claimed },
                        overPort443 = settings.current().sshOverPort443,
                        loaded = true,
                    )
            }

        fun generate(vaultId: Long) =
            viewModelScope.launch {
                _state.value = _state.value.copy(busy = true)
                runCatching { keys.generate(vaultId) }
                // No refresh here: generating bumps the store's revision, and
                // the collector above rebuilds from that.
                _state.value = _state.value.copy(busy = false)
            }

        /**
         * Tries the key against its own repository.
         *
         * Deliberately the whole handshake and nothing more: it proves the host
         * accepted this exact key, which is the only thing that distinguishes a
         * key that was never registered from one the network could not reach.
         */
        fun test(row: SshKeyRow) =
            viewModelScope.launch {
                update(row.vault.id, KeyCheck.Running)
                val outcome = repository.testSshKey(row.vault)
                update(
                    row.vault.id,
                    outcome.fold(
                        onSuccess = { KeyCheck.Passed(it) },
                        onFailure = { KeyCheck.Failed(it.message ?: it::class.simpleName.orEmpty()) },
                    ),
                )
            }

        fun deleteOrphan(fileName: String) = viewModelScope.launch { keys.deleteByFileName(fileName) }

        fun setOverPort443(enabled: Boolean) =
            viewModelScope.launch {
                settings.setSshOverPort443(enabled)
                refresh()
            }

        private fun update(
            vaultId: Long,
            check: KeyCheck,
        ) {
            _state.value =
                _state.value.copy(
                    repositories =
                        _state.value.repositories.map {
                            if (it.vault.id == vaultId) it.copy(check = check) else it
                        },
                )
        }
    }
