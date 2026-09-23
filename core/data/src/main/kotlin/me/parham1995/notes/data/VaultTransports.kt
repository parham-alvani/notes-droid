package me.parham1995.notes.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.GitSshVaultSync
import me.parham1995.notes.data.git.SshKeyStore
import me.parham1995.notes.sync.GitHubClient
import me.parham1995.notes.sync.GitHubConfig
import me.parham1995.notes.sync.RestVaultSync
import me.parham1995.notes.sync.RestVaultWriter
import me.parham1995.notes.sync.VaultFilter
import me.parham1995.notes.sync.VaultSync
import me.parham1995.notes.sync.VaultWriter
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a transport for one vault.
 *
 * Extracted because reading and writing need exactly the same three things --
 * the credential, the URL, and in the SSH case a working tree that is already
 * cloned -- and getting a second copy of that wrong is how a vault ends up
 * reading from one repository and writing to another.
 *
 * Everything here is built fresh each time rather than cached, so a token
 * pasted or a port switched in Settings takes effect on the next operation
 * rather than on the next launch.
 */
@Singleton
open class VaultTransports
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val tokens: TokenStore,
        private val files: VaultFileStore,
        private val sshKeys: SshKeyStore,
        private val log: SyncLog,
        @param:ApplicationContext private val context: Context,
        private val http: OkHttpClient,
        private val vaults: VaultDao,
    ) {
        suspend fun client(vault: VaultEntity): GitHubClient {
            val token = tokens.token() ?: throw NotConfiguredException("no access token stored")
            return GitHubClient(
                GitHubConfig(
                    owner = vault.owner,
                    repo = vault.repo,
                    branch = vault.branch,
                    token = token,
                ),
                http = http,
            )
        }

        /**
         * GitHub answers SSH on 443 as well as 22, which is the way round a
         * network that blocks 22 -- otherwise the clone just hangs.
         */
        suspend fun sshUrl(vault: VaultEntity): String =
            if (settings.current().sshOverPort443) {
                "ssh://git@ssh.github.com:443/" + vault.owner + "/" + vault.repo + ".git"
            } else {
                "git@github.com:" + vault.owner + "/" + vault.repo + ".git"
            }

        /**
         * Moves keys named after the old scheme to the one keyed by vault id.
         * Given every vault, because which one was added first decides who
         * keeps a file two names shared. See [SshKeyStore.adoptLegacyNames].
         */
        suspend fun adoptLegacyKeys() {
            val all = vaults.all()
            withContext(Dispatchers.IO) { sshKeys.adoptLegacyNames(all) }
        }

        suspend fun ssh(vault: VaultEntity): GitSshVaultSync {
            adoptLegacyKeys()
            return GitSshVaultSync(
                // Each repository gets its own working tree, which is what lets
                // one key serve all of them without their histories colliding.
                workTree = files.rootOf(vault.id),
                remoteUrl = sshUrl(vault),
                branch = vault.branch ?: DEFAULT_BRANCH,
                keys = sshKeys,
                vaultId = vault.id,
                configDir = File(context.filesDir, "git"),
                log = log::info,
            )
        }

        /**
         * The read half of whichever transport this vault syncs over.
         *
         * Open for the same reason as [writer]: what a sync does around the
         * transport is worth testing, and unreachable behind a real one.
         */
        open suspend fun reader(vault: VaultEntity): VaultSync =
            when (SyncTransport.parse(vault.transport)) {
                SyncTransport.REST -> {
                    val client = client(vault)
                    RestVaultSync(
                        client = client,
                        branch = vault.branch ?: client.repository().defaultBranch,
                        filter = VaultFilter(),
                        log = log::info,
                    )
                }

                SyncTransport.SSH -> {
                    adoptLegacyKeys()
                    if (!sshKeys.exists(vault.id)) {
                        throw NotConfiguredException(
                            "no SSH key for ${vault.label} yet - generate one in Settings and add it " +
                                "as a deploy key on that repository",
                        )
                    }
                    ssh(vault)
                }
            }

        /**
         * The write half of whichever transport this vault syncs over.
         *
         * Deliberately the same transport as the read: writing over REST while
         * reading over SSH would mean a commit the working tree knows nothing
         * about, and the next sync resetting straight over the top of it.
         *
         * Open so a test can stand a transport in that never touches the
         * network. The queue and the re-apply are the parts worth testing, and
         * they are unreachable behind a real GitHub client.
         */
        open suspend fun writer(vault: VaultEntity): VaultWriter =
            when (SyncTransport.parse(vault.transport)) {
                SyncTransport.REST -> {
                    val client = client(vault)
                    RestVaultWriter(
                        client = client,
                        branch = vault.branch ?: client.repository().defaultBranch,
                        log = log::info,
                    )
                }

                SyncTransport.SSH -> {
                    adoptLegacyKeys()
                    if (!sshKeys.exists(vault.id)) {
                        throw NotConfiguredException("no SSH key for ${vault.label} yet")
                    }
                    ssh(vault)
                }
            }

        private companion object {
            const val DEFAULT_BRANCH = "main"
        }
    }
