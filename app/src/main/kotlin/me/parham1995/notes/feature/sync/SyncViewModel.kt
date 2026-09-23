package me.parham1995.notes.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.BrowserSort
import me.parham1995.notes.data.CrashLog
import me.parham1995.notes.data.ImagePolicy
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.StartScreen
import me.parham1995.notes.data.SyncLog
import me.parham1995.notes.data.SyncRepository
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.TokenStore
import me.parham1995.notes.data.VaultFileStore
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.database.PendingEditEntity
import me.parham1995.notes.data.database.SyncLogEntity
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.SshKeyStore
import javax.inject.Inject

/** One repository's SSH key, since a deploy key serves exactly one. */
data class VaultKey(
    val vaultId: Long,
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
    /** Edits made on the device that have not reached the repository yet. */
    val queuedEdits: List<PendingEditEntity> = emptyList(),
    /** Result of the last write-access check, for immediate feedback. */
    val writeMessage: String? = null,
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
        private val writes: VaultWriteRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(LocalState())

        private data class LocalState(
            val hasToken: Boolean = false,
            val diskBytes: Long = 0,
            val connectionMessage: String? = null,
            val generatingKey: Boolean = false,
            val reindexing: Boolean = false,
            val lastCrash: String? = null,
            val writeMessage: String? = null,
        )

        /**
         * One entry per repository set to SSH, kept current.
         *
         * Follows the repositories *and* the key files: adding a repository
         * changes the first, generating a key changes only the second, and a
         * list built from a snapshot missed whichever happened after it was
         * taken. GitHub refuses the same deploy key on a second repository, so
         * there is a key each and each has to be registered.
         */
        private val sshKeyRows: Flow<List<VaultKey>> =
            combine(repository.vaults(), sshKeys.revision) { vaults, _ ->
                vaults
                    .filter { SyncTransport.parse(it.transport) == SyncTransport.SSH }
                    .map { vault ->
                        val line = sshKeys.publicKeyLine(vault.id)
                        VaultKey(
                            vaultId = vault.id,
                            label = vault.label,
                            publicKey = line,
                            fingerprint = line?.let { sshKeys.fingerprint(vault.id) },
                        )
                    }
            }

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
                        generatingKey = extra.generatingKey,
                        reindexing = extra.reindexing,
                        lastCrash = extra.lastCrash,
                        writeMessage = extra.writeMessage,
                        tokenRejected =
                            failed?.outputData?.getString(SyncWorker.KEY_ERROR) == SyncWorker.TOKEN_REJECTED,
                    )
                },
                // Nested rather than a sixth argument: `combine` is typed up to
                // five, and the vararg form loses every type in the lambda.
                repository.vaults(),
                sshKeyRows,
                writes.queue(),
            ) { base, vaults, keys, queued ->
                base.copy(vaults = vaults, sshKeys = keys, queuedEdits = queued)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncUiState())

        init {
            refreshLocal()
        }

        /**
         * Adds a vault.
         *
         * The name is for reading and nothing else -- it does not decide where
         * anything is stored, so it can be changed later without moving two
         * hundred megabytes. Blank falls back to the repository's own name.
         */
        fun addVault(
            owner: String,
            repo: String,
            branch: String,
            name: String,
        ) = viewModelScope.launch {
            val first = state.value.vaults.isEmpty()
            repository.addVault(
                owner = owner,
                repo = repo,
                branch = branch.ifBlank { null },
                name = name,
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
            repository.setTransport(vault.id, transport)
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

        fun setAuthor(
            name: String,
            email: String,
        ) = viewModelScope.launch { settingsStore.setAuthor(name, email) }

        fun setScratchpad(
            path: String,
            vaultId: Long,
        ) = viewModelScope.launch { settingsStore.setScratchpad(path, vaultId) }

        /**
         * Asks each repository again whether this device may push to it.
         *
         * The answer is what every write affordance in the app is gated on, so
         * it is worth being able to ask outright rather than waiting for the
         * next background sync to find out.
         */
        fun checkWriteAccess() =
            viewModelScope.launch {
                local.value = local.value.copy(writeMessage = "checking...")
                val message =
                    runCatching { repository.refreshWriteAccess() }
                        .getOrElse { it.message ?: "could not check" }
                local.value = local.value.copy(writeMessage = message)
            }

        /** Tries the queue again now, rather than waiting for the next sync. */
        fun sendQueuedEdits() =
            viewModelScope.launch {
                val sent = runCatching { writes.flush() }.getOrDefault(0)
                local.value =
                    local.value.copy(
                        writeMessage = if (sent > 0) "sent $sent edit(s)" else "nothing could be sent yet",
                    )
            }

        fun discardEdit(id: Long) = viewModelScope.launch { writes.discard(id) }

        fun setTransport(transport: SyncTransport) = viewModelScope.launch { settingsStore.setTransport(transport) }

        fun setSshOverPort443(enabled: Boolean) = viewModelScope.launch { settingsStore.setSshOverPort443(enabled) }

        fun setImagePolicy(policy: ImagePolicy) = viewModelScope.launch { settingsStore.setImagePolicy(policy) }

        fun setBackgroundSync(enabled: Boolean) = viewModelScope.launch { settingsStore.setBackgroundSync(enabled) }

        fun setSyncIntervalHours(hours: Int) = viewModelScope.launch { settingsStore.setSyncIntervalHours(hours) }

        fun setSyncOnWifiOnly(enabled: Boolean) = viewModelScope.launch { settingsStore.setSyncOnWifiOnly(enabled) }

        fun setTaskDigest(enabled: Boolean) = viewModelScope.launch { settingsStore.setTaskDigest(enabled) }

        fun setTaskDigestHour(hour: Int) = viewModelScope.launch { settingsStore.setTaskDigestHour(hour) }

        fun setTextScale(scale: Float) = viewModelScope.launch { settingsStore.setTextScale(scale) }

        fun setLineSpacing(spacing: Float) = viewModelScope.launch { settingsStore.setLineSpacing(spacing) }

        fun setTheme(theme: ThemeChoice) = viewModelScope.launch { settingsStore.setTheme(theme) }

        fun setPersianFont(enabled: Boolean) = viewModelScope.launch { settingsStore.setPersianFont(enabled) }

        fun setStylusSpotlight(enabled: Boolean) = viewModelScope.launch { settingsStore.setStylusSpotlight(enabled) }

        fun setHideCompletedTasks(enabled: Boolean) =
            viewModelScope.launch { settingsStore.setHideCompletedTasks(enabled) }

        fun setStartScreen(screen: StartScreen) = viewModelScope.launch { settingsStore.setStartScreen(screen) }

        fun setBrowserSort(sort: BrowserSort) = viewModelScope.launch { settingsStore.setBrowserSort(sort) }

        /**
         * The private half never leaves the device; only this public line does,
         * and it goes to GitHub as a read-only deploy key.
         */
        fun generateSshKey(vaultId: Long) =
            viewModelScope.launch {
                local.value = local.value.copy(generatingKey = true)
                runCatching { sshKeys.generate(vaultId) }
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
                    )
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
