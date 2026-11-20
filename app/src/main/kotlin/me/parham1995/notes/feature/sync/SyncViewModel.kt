package me.parham1995.notes.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.BrowserSort
import me.parham1995.notes.data.CloneSettings
import me.parham1995.notes.data.CrashLog
import me.parham1995.notes.data.FoldSections
import me.parham1995.notes.data.ImagePolicy
import me.parham1995.notes.data.LineWidth
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SettingsWriter
import me.parham1995.notes.data.StartScreen
import me.parham1995.notes.data.SyncLog
import me.parham1995.notes.data.SyncRepository
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.TokenStore
import me.parham1995.notes.data.VaultClones
import me.parham1995.notes.data.VaultFileStore
import me.parham1995.notes.data.VaultOverlay
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.database.PendingEditEntity
import me.parham1995.notes.data.database.SyncLogEntity
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.SshKeyStore
import me.parham1995.notes.obsidian.HiddenFiles
import me.parham1995.notes.ui.UiText
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
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** Actually working right now. */
    val running: Boolean = false,
    /** Queued or backing off between retries -- not the same as working. */
    val queued: Boolean = false,
    val attempt: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    /** Null until measured, which is only when the sync section asks. */
    val diskBytes: Long? = null,
    /** Result of the last "test connection", for immediate feedback. */
    val connectionMessage: UiText? = null,
    val tokenRejected: Boolean = false,
    val sshKeys: List<VaultKey> = emptyList(),
    val generatingKey: Boolean = false,
    val reindexing: Boolean = false,
    /** The last crash, if the app has had one. Null is the normal case. */
    val lastCrash: String? = null,
    /** Edits made on the device that have not reached the repository yet. */
    val queuedEdits: List<PendingEditEntity> = emptyList(),
    /** Result of the last write-access check, for immediate feedback. */
    val writeMessage: UiText? = null,
    /** The vaults that are clones, and what each clone's own git config says. */
    val clones: Map<Long, CloneSettings> = emptyMap(),
    /** What came of the last change of a vault's address, by the vault it was for. */
    val addressMessage: Pair<Long, UiText>? = null,
) {
    /** Whether the vault being read is a read-only clone: its sync is a pull. */
    val pullsOnly: Boolean
        get() {
            val active = vaults.firstOrNull { it.id == settings.activeVaultId } ?: vaults.firstOrNull()
            return active != null && clones[active.id]?.readOnly == true
        }
}

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
        private val vaultClones: VaultClones,
        private val writer: SettingsWriter,
    ) : ViewModel() {
        private val local = MutableStateFlow(LocalState())

        private data class LocalState(
            val hasToken: Boolean = false,
            val diskBytes: Long? = null,
            val connectionMessage: UiText? = null,
            val generatingKey: Boolean = false,
            val reindexing: Boolean = false,
            val lastCrash: String? = null,
            val writeMessage: UiText? = null,
            val addressMessage: Pair<Long, UiText>? = null,
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
                // Reading key files and parsing them with sshd is disk and
                // crypto work, and a combine runs on whatever collects it --
                // the main thread, for a screen.
                .flowOn(Dispatchers.IO)

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
                        addressMessage = extra.addressMessage,
                        tokenRejected =
                            failed?.outputData?.getString(SyncWorker.KEY_ERROR) == SyncWorker.TOKEN_REJECTED,
                    )
                },
                // Nested rather than a sixth argument: `combine` is typed up to
                // five, and the vararg form loses every type in the lambda.
                repository.vaults(),
                sshKeyRows,
                // With whether a changed setting can go to the vault being read.
                combine(writes.queue(), writes.writable) { queued, writable -> queued to writable },
                vaultClones.clones,
            ) { base, vaults, keys, (queued, writable), clones ->
                val active = vaults.firstOrNull { it.id == base.settings.activeVaultId } ?: vaults.firstOrNull()
                val fromVault = base.settings.fromVault.copy(writable = active != null && active.id in writable)
                base.copy(
                    settings = base.settings.copy(fromVault = fromVault),
                    vaults = vaults,
                    sshKeys = keys,
                    queuedEdits = queued,
                    clones = clones,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncUiState())

        init {
            refreshCredentials()
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
                refreshCredentials()
            }

        fun clearToken() =
            viewModelScope.launch {
                tokenStore.clear()
                refreshCredentials()
            }

        fun testConnection() =
            viewModelScope.launch {
                val message =
                    runCatching { repository.testConnection() }
                        .fold(
                            onSuccess = {
                                UiText.Resource(
                                    if (it.private) R.string.connection_ok_private else R.string.connection_ok_public,
                                    listOf(it.fullName, it.pushedAt.orEmpty()),
                                )
                            },
                            onFailure = { failure ->
                                failure.message?.let(UiText::Raw) ?: UiText.Resource(R.string.connection_failed)
                            },
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
        ) = viewModelScope.launch {
            // The path is a setting a vault may hold; which vault holds the scratchpad is this phone's.
            writer.set(VaultOverlay.scratchpad, path)
            settingsStore.setScratchpadVault(vaultId)
        }

        /**
         * Keep on This Device: the key is declined, and its value in use is
         * written to this phone, so the vault's line no longer decides it.
         */
        fun keepOnDevice(key: String) =
            viewModelScope.launch {
                VaultOverlay.BINDINGS.firstOrNull { it.id == key }?.let { writer.keepOnDevice(it) }
                    ?: settingsStore.setVaultKeyDeclined(key, declined = true)
            }

        /** Follow the Vault: the key is no longer declined. */
        fun followVault(key: String) =
            viewModelScope.launch { settingsStore.setVaultKeyDeclined(key, declined = false) }

        /** Who Mark as Verified names; empty for the author's own name in the email. */
        fun setOkfActor(actor: String) = viewModelScope.launch { settingsStore.setOkfActor(actor) }

        /** Which vault holds the scratchpad, when its path is the vault's to say and not this phone's. */
        fun setScratchpadVault(vaultId: Long) = viewModelScope.launch { settingsStore.setScratchpadVault(vaultId) }

        /**
         * Makes a vault's clone read-only, or not. It waits for a sync that
         * is running, which asks the clone which it is more than once.
         */
        fun setReadOnly(
            vaultId: Long,
            readOnly: Boolean,
        ) = viewModelScope.launch { vaultClones.setReadOnly(vaultId, readOnly) }

        /** Sets where a vault's clone pulls from; an empty address is refused and the old one stays. */
        fun setAddress(
            vaultId: Long,
            address: String,
        ) = viewModelScope.launch {
            val saved = runCatching { vaultClones.setAddress(vaultId, address) }.getOrDefault(false)
            val said = if (saved) R.string.vault_address_saved else R.string.vault_address_refused
            local.update { it.copy(addressMessage = vaultId to UiText.Resource(said)) }
        }

        /** Whether this phone reads the app's settings from a vault at all. */
        fun setFollowVault(follow: Boolean) = viewModelScope.launch { settingsStore.setFollowVault(follow) }

        /** Declines one key the vault sets, or follows it again. */
        fun setVaultKeyDeclined(
            key: String,
            declined: Boolean,
        ) = viewModelScope.launch { settingsStore.setVaultKeyDeclined(key, declined) }

        /**
         * Asks each repository again whether this device may push to it.
         *
         * The answer is what every write affordance in the app is gated on, so
         * it is worth being able to ask outright rather than waiting for the
         * next background sync to find out.
         */
        fun checkWriteAccess() =
            viewModelScope.launch {
                local.value = local.value.copy(writeMessage = UiText.Resource(R.string.write_checking))
                val message =
                    runCatching { UiText.Raw(repository.refreshWriteAccess()) }
                        .getOrElse { failure ->
                            failure.message?.let(UiText::Raw) ?: UiText.Resource(R.string.write_check_failed)
                        }
                local.value = local.value.copy(writeMessage = message)
            }

        /** Tries the queue again now, rather than waiting for the next sync. */
        fun sendQueuedEdits() =
            viewModelScope.launch {
                val sent = runCatching { writes.flush() }.getOrDefault(0)
                local.value =
                    local.value.copy(
                        writeMessage =
                            if (sent > 0) {
                                UiText.Plural(R.plurals.write_sent, sent)
                            } else {
                                UiText.Resource(R.string.write_nothing_sent)
                            },
                    )
            }

        fun discardEdit(id: Long) = viewModelScope.launch { writes.discard(id) }

        fun setTransport(transport: SyncTransport) = viewModelScope.launch { settingsStore.setTransport(transport) }

        fun setSshOverPort443(enabled: Boolean) = viewModelScope.launch { writer.set(VaultOverlay.sshPort443, enabled) }

        fun setImagePolicy(policy: ImagePolicy) = viewModelScope.launch { writer.set(VaultOverlay.imagePolicy, policy) }

        fun setBackgroundSync(enabled: Boolean) =
            viewModelScope.launch {
                // One key in the vault, minutes between syncs, for the switch and the hours both: 0 is off.
                writer.set(VaultOverlay.syncInterval, if (enabled) settingsStore.current().syncIntervalHours else 0)
            }

        fun setSyncIntervalHours(hours: Int) = viewModelScope.launch { writer.set(VaultOverlay.syncInterval, hours) }

        fun setSyncOnWifiOnly(enabled: Boolean) = viewModelScope.launch { writer.set(VaultOverlay.wifiOnly, enabled) }

        fun setTaskDigest(enabled: Boolean) = viewModelScope.launch { writer.set(VaultOverlay.digest, enabled) }

        fun setAppLock(enabled: Boolean) = viewModelScope.launch { settingsStore.setAppLock(enabled) }

        fun setLockPicker(enabled: Boolean) = viewModelScope.launch { settingsStore.setLockPicker(enabled) }

        fun setTaskDigestHour(hour: Int) = viewModelScope.launch { writer.set(VaultOverlay.digestHour, hour) }

        fun setTextScale(scale: Float) = viewModelScope.launch { writer.setWhenStill(VaultOverlay.textSize, scale) }

        fun setLineSpacing(spacing: Float) =
            viewModelScope.launch { writer.setWhenStill(VaultOverlay.lineSpacing, spacing) }

        fun setLineWidth(width: LineWidth) =
            viewModelScope.launch { writer.setWhenStill(VaultOverlay.lineWidth, width) }

        fun setTheme(theme: ThemeChoice) = viewModelScope.launch { writer.set(VaultOverlay.theme, theme) }

        fun setWidgetOpacity(percent: Int) =
            viewModelScope.launch { writer.setWhenStill(VaultOverlay.widgetOpacity, percent) }

        fun setPersianFont(enabled: Boolean) = viewModelScope.launch { writer.set(VaultOverlay.persianFont, enabled) }

        fun setStylusSpotlight(enabled: Boolean) =
            viewModelScope.launch { writer.set(VaultOverlay.stylusSpotlight, enabled) }

        fun setHideCompletedTasks(enabled: Boolean) =
            viewModelScope.launch { writer.set(VaultOverlay.hideCompleted, enabled) }

        fun setHoldToDefine(enabled: Boolean) = viewModelScope.launch { writer.set(VaultOverlay.holdToDefine, enabled) }

        fun setRemoteImages(enabled: Boolean) = viewModelScope.launch { writer.set(VaultOverlay.remoteImages, enabled) }

        fun setFoldSections(choice: FoldSections) =
            viewModelScope.launch { writer.set(VaultOverlay.foldSections, choice) }

        // The notes are marked again by the application, which watches both:
        // a change made anywhere else -- adb, a restore -- is followed too.
        fun setHideAgentFiles(enabled: Boolean) =
            viewModelScope.launch {
                // In the vault it is the list of names: the agents' files, or none.
                writer.set(VaultOverlay.hiddenFiles, if (enabled) HiddenFiles.AGENT_FILES else emptySet())
            }

        fun setShowHiddenFiles(enabled: Boolean) =
            viewModelScope.launch { writer.set(VaultOverlay.showHidden, enabled) }

        fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { writer.set(VaultOverlay.keepScreenOn, enabled) }

        fun setTapForFullScreen(enabled: Boolean) =
            viewModelScope.launch { writer.set(VaultOverlay.tapFullScreen, enabled) }

        fun setStartScreen(screen: StartScreen) = viewModelScope.launch { writer.set(VaultOverlay.startScreen, screen) }

        fun setBrowserSort(sort: BrowserSort) = viewModelScope.launch { writer.set(VaultOverlay.browserSort, sort) }

        /**
         * The private half never leaves the device; only this public line does,
         * and it goes to GitHub as a read-only deploy key.
         */
        fun generateSshKey(vaultId: Long) =
            viewModelScope.launch {
                local.value = local.value.copy(generatingKey = true)
                runCatching { sshKeys.generate(vaultId) }
                local.value = local.value.copy(generatingKey = false)
                refreshCredentials()
            }

        fun dismissCrash() =
            viewModelScope.launch {
                crashLog.clear()
                refreshCredentials()
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

        /** Everything read off the device, including the walk of the vault. */
        fun refreshLocal() {
            refreshCredentials()
            measureDisk()
        }

        /**
         * The cheap reads: whether there is a token, and whether the app
         * crashed. Every settings screen needs these, so they are read when
         * the view model is made.
         */
        private fun refreshCredentials() =
            viewModelScope.launch {
                val hasToken = tokenStore.hasToken()
                val crash = crashLog.read()
                local.update { it.copy(hasToken = hasToken, lastCrash = crash) }
            }

        /**
         * How much the vaults take on disk, which means visiting every file.
         *
         * Asked for by the one card that shows it rather than done on
         * creation. The settings home and each section have a view model of
         * their own, so doing it in init walked the whole vault twice on every
         * visit to Settings to show a number on one screen.
         */
        fun measureDisk() =
            viewModelScope.launch {
                val bytes = files.sizeOnDisk()
                local.update { it.copy(diskBytes = bytes) }
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
