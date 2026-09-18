package me.parham1995.notes.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.CrashLog
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
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.SshKeyStore
import javax.inject.Inject

/** One repository's SSH key, since a deploy key serves exactly one. */
data class VaultKey(
    val mount: String,
    val label: String,
    val publicKey: String?,
    val fingerprint: String?,
)

data class SyncUiState(
    val settings: VaultSettings = VaultSettings(),
    val vaults: List<VaultEntity> = emptyList(),
    val hasToken: Boolean = false,
    val noteCount: Int = 0,
    val headCommit: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** Actually working right now. */
    val running: Boolean = false,
    /** Queued or backing off between retries -- not the same as working. */
    val queued: Boolean = false,
    val attempt: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    val diskBytes: Long = 0,
    /** Result of the last "test connection", for immediate feedback. */
    val connectionMessage: String? = null,
    val tokenRejected: Boolean = false,
    val sshKeys: List<VaultKey> = emptyList(),
    val generatingKey: Boolean = false,
    val reindexing: Boolean = false,
    /** The last crash, if the app has had one. Null is the normal case. */
    val lastCrash: String? = null,
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
        private val crashLog: CrashLog,
    ) : ViewModel() {
        private val local = MutableStateFlow(LocalState())

        private data class LocalState(
            val hasToken: Boolean = false,
            val diskBytes: Long = 0,
            val connectionMessage: String? = null,
            val sshKeys: List<VaultKey> = emptyList(),
            val generatingKey: Boolean = false,
            val reindexing: Boolean = false,
            val lastCrash: String? = null,
        )

        val state: StateFlow<SyncUiState> =
            combine(
                combine(
                    settingsStore.settings,
                    repository.status,
                    repository.noteCount,
                    scheduler.observe(),
                    local,
                ) { settings, status, notes, work, extra ->
                    val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val pending = work.firstOrNull { !it.state.isFinished }
                    val failed = work.firstOrNull { it.state == WorkInfo.State.FAILED }
                    SyncUiState(
                        settings = settings,
                        hasToken = extra.hasToken,
                        noteCount = notes,
                        headCommit = status.headCommit,
                        lastSyncAt = status.lastSyncAt,
                        lastError = failed?.outputData?.getString(SyncWorker.KEY_ERROR) ?: status.lastError,
                        running = running != null,
                        queued = pending != null && running == null,
                        attempt = pending?.runAttemptCount ?: 0,
                        done = running?.progress?.getInt(SyncWorker.KEY_DONE, 0) ?: 0,
                        total = running?.progress?.getInt(SyncWorker.KEY_TOTAL, 0) ?: 0,
                        diskBytes = extra.diskBytes,
                        connectionMessage = extra.connectionMessage,
                        sshKeys = extra.sshKeys,
                        generatingKey = extra.generatingKey,
                        reindexing = extra.reindexing,
                        lastCrash = extra.lastCrash,
                        tokenRejected =
                            failed?.outputData?.getString(SyncWorker.KEY_ERROR) == SyncWorker.TOKEN_REJECTED,
                    )
                },
                // Nested rather than a sixth argument: `combine` is typed up to
                // five, and the vararg form loses every type in the lambda.
                repository.vaults(),
            ) { base, vaults ->
                base.copy(vaults = vaults)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncUiState())

        init {
            refreshLocal()
        }

        /**
         * Adds a repository.
         *
         * The first one mounts at the root, so an install that only ever reads
         * one looks and behaves exactly as it did before any of this existed.
         * Everything after it needs a folder of its own, because two
         * repositories cannot both own the top level.
         */
        fun addVault(
            owner: String,
            repo: String,
            branch: String,
            mount: String,
        ) = viewModelScope.launch {
            val first = state.value.vaults.isEmpty()
            val folder = if (first) mount.trim() else mount.trim().ifBlank { repo.trim() }
            repository.addVault(
                owner = owner,
                repo = repo,
                branch = branch.ifBlank { null },
                mount = folder,
                transport = state.value.settings.transport,
            )
            // Kept in step so the old single-repository settings still describe
            // the first vault, which is what the token screen reads.
            if (first) settingsStore.setRepository(owner, repo, branch.ifBlank { null })
        }

        fun removeVault(id: Long) = viewModelScope.launch { repository.removeVault(id) }

        fun setVaultTransport(
            vault: VaultEntity,
            transport: SyncTransport,
        ) = viewModelScope.launch {
            repository.updateVault(vault.copy(transport = transport.name))
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

        fun setSshOverPort443(enabled: Boolean) = viewModelScope.launch { settingsStore.setSshOverPort443(enabled) }

        fun setImagePolicy(policy: ImagePolicy) = viewModelScope.launch { settingsStore.setImagePolicy(policy) }

        fun setBackgroundSync(enabled: Boolean) = viewModelScope.launch { settingsStore.setBackgroundSync(enabled) }

        fun setSyncIntervalHours(hours: Int) = viewModelScope.launch { settingsStore.setSyncIntervalHours(hours) }

        fun setSyncOnWifiOnly(enabled: Boolean) = viewModelScope.launch { settingsStore.setSyncOnWifiOnly(enabled) }

        fun setTaskDigest(enabled: Boolean) = viewModelScope.launch { settingsStore.setTaskDigest(enabled) }

        fun setTaskDigestHour(hour: Int) = viewModelScope.launch { settingsStore.setTaskDigestHour(hour) }

        /**
         * The private half never leaves the device; only this public line does,
         * and it goes to GitHub as a read-only deploy key.
         */
        fun generateSshKey(mount: String) =
            viewModelScope.launch {
                local.value = local.value.copy(generatingKey = true)
                runCatching { sshKeys.generate(mount) }
                local.value = local.value.copy(generatingKey = false)
                refreshLocal()
            }

        fun dismissCrash() =
            viewModelScope.launch {
                crashLog.clear()
                refreshLocal()
            }

        fun reindex() =
            viewModelScope.launch {
                local.value = local.value.copy(reindexing = true)
                runCatching { repository.reindex() }
                local.value = local.value.copy(reindexing = false)
                refreshLocal()
            }

        fun cancelSync() = viewModelScope.launch { scheduler.cancel() }

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
                        lastCrash = crashLog.read(),
                        // One entry per repository set to SSH. GitHub refuses
                        // the same deploy key on a second repository, so there
                        // is a key each and each has to be registered.
                        sshKeys =
                            repository
                                .vaults()
                                .first()
                                .filter { SyncTransport.parse(it.transport) == SyncTransport.SSH }
                                .map { vault ->
                                    VaultKey(
                                        mount = vault.mount,
                                        label = vault.label,
                                        publicKey = sshKeys.publicKeyLine(vault.mount),
                                        fingerprint =
                                            sshKeys
                                                .publicKeyLine(vault.mount)
                                                ?.let { sshKeys.fingerprint(vault.mount) },
                                    )
                                },
                    )
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
