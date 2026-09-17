package me.parham1995.notes.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.ImagePolicy
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncLog
import me.parham1995.notes.data.SyncRepository
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.data.TokenStore
import me.parham1995.notes.data.VaultFileStore
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.data.database.SyncLogEntity
import me.parham1995.notes.data.git.SshKeyStore
import javax.inject.Inject

data class SyncUiState(
    val settings: VaultSettings = VaultSettings(),
    val hasToken: Boolean = false,
    val noteCount: Int = 0,
    val headCommit: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val diskBytes: Long = 0,
    /** Result of the last "test connection", for immediate feedback. */
    val connectionMessage: String? = null,
    val tokenRejected: Boolean = false,
    val sshPublicKey: String? = null,
    val generatingKey: Boolean = false,
)

@HiltViewModel
class SyncViewModel
    @Inject
    constructor(
        private val settingsStore: SettingsStore,
        private val tokenStore: TokenStore,
        private val repository: SyncRepository,
        private val scheduler: SyncScheduler,
        private val files: VaultFileStore,
        private val sshKeys: SshKeyStore,
        private val syncLog: SyncLog,
    ) : ViewModel() {
        private val local = MutableStateFlow(LocalState())

        private data class LocalState(
            val hasToken: Boolean = false,
            val diskBytes: Long = 0,
            val connectionMessage: String? = null,
            val sshPublicKey: String? = null,
            val generatingKey: Boolean = false,
        )

        val state: StateFlow<SyncUiState> =
            combine(
                settingsStore.settings,
                repository.status,
                repository.noteCount,
                scheduler.observe(),
                local,
            ) { settings, status, notes, work, extra ->
                val active = work.firstOrNull { !it.state.isFinished }
                val failed = work.firstOrNull { it.state == WorkInfo.State.FAILED }
                SyncUiState(
                    settings = settings,
                    hasToken = extra.hasToken,
                    noteCount = notes,
                    headCommit = status.headCommit,
                    lastSyncAt = status.lastSyncAt,
                    lastError = failed?.outputData?.getString(SyncWorker.KEY_ERROR) ?: status.lastError,
                    running = active != null,
                    done = active?.progress?.getInt(SyncWorker.KEY_DONE, 0) ?: 0,
                    total = active?.progress?.getInt(SyncWorker.KEY_TOTAL, 0) ?: 0,
                    diskBytes = extra.diskBytes,
                    connectionMessage = extra.connectionMessage,
                    sshPublicKey = extra.sshPublicKey,
                    generatingKey = extra.generatingKey,
                    tokenRejected =
                        failed?.outputData?.getString(SyncWorker.KEY_ERROR) == SyncWorker.TOKEN_REJECTED,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncUiState())

        init {
            refreshLocal()
        }

        fun saveRepository(
            owner: String,
            repo: String,
            branch: String,
        ) = viewModelScope.launch {
            settingsStore.setRepository(owner, repo, branch.ifBlank { null })
        }

        fun saveToken(token: String) =
            viewModelScope.launch {
                tokenStore.setToken(token.trim())
                refreshLocal()
            }

        fun clearToken() =
            viewModelScope.launch {
                tokenStore.clear()
                refreshLocal()
            }

        fun testConnection() =
            viewModelScope.launch {
                val message =
                    runCatching { repository.testConnection() }
                        .fold(
                            onSuccess = {
                                "${it.fullName} - ${if (it.private) "private" else "public"}, updated ${it.pushedAt}"
                            },
                            onFailure = { it.message ?: "connection failed" },
                        )
                local.value = local.value.copy(connectionMessage = message)
            }

        /** The on-device sync journal, newest first. */
        val log: StateFlow<List<SyncLogEntity>> =
            syncLog
                .recent()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        fun clearLog() = viewModelScope.launch { syncLog.clear() }

        fun setTransport(transport: SyncTransport) = viewModelScope.launch { settingsStore.setTransport(transport) }

        fun setImagePolicy(policy: ImagePolicy) = viewModelScope.launch { settingsStore.setImagePolicy(policy) }

        /**
         * The private half never leaves the device; only this public line does,
         * and it goes to GitHub as a read-only deploy key.
         */
        fun generateSshKey() =
            viewModelScope.launch {
                local.value = local.value.copy(generatingKey = true)
                val line = runCatching { sshKeys.generate() }.getOrElse { it.message ?: "key generation failed" }
                local.value = local.value.copy(sshPublicKey = line, generatingKey = false)
            }

        fun syncNow() =
            viewModelScope.launch {
                scheduler.syncNow(settingsStore.current().syncOnWifiOnly)
            }

        fun reset() =
            viewModelScope.launch {
                repository.reset()
                files.clear()
                refreshLocal()
            }

        fun refreshLocal() =
            viewModelScope.launch {
                local.value =
                    local.value.copy(
                        hasToken = tokenStore.hasToken(),
                        diskBytes = files.sizeOnDisk(),
                        sshPublicKey = sshKeys.publicKeyLine(),
                    )
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
