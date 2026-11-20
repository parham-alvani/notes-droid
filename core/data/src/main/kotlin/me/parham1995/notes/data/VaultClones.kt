package me.parham1995.notes.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.git.AndroidGitEnvironment
import me.parham1995.notes.data.git.CloneConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which vaults are read-only: clones that pull and never write.
 *
 * An interface for the write path's sake: its tests have no clone to ask.
 */
interface ReadOnlyRules {
    /** The read-only vaults, kept current, for whatever shows it. */
    val readOnly: Flow<Set<Long>>

    /** Whether [vaultId] is read-only now, asked of the clone itself. */
    suspend fun isReadOnly(vaultId: Long): Boolean

    companion object {
        /** No vault is read-only. */
        val NEVER =
            object : ReadOnlyRules {
                override val readOnly: Flow<Set<Long>> = flowOf(emptySet())

                override suspend fun isReadOnly(vaultId: Long): Boolean = false
            }
    }
}

/** What one vault's clone says of itself in its own git config. */
data class CloneSettings(
    val readOnly: Boolean = false,
    /** Where it pulls from and pushes to. Null when it has no remote. */
    val address: String? = null,
)

/**
 * The vaults that are clones, and what each clone's own git config says:
 * whether it is read-only (`daftar.readonly`) and its address
 * (`remote.<name>.url`). `read-only-vault.md` in notes-linux's `docs/specs/`
 * has the rules.
 *
 * Only a vault synced over SSH is a clone. One synced over REST is files
 * fetched through GitHub's API with no repository under them, so it has none
 * of these settings: it is never read-only, and Settings does not offer them.
 *
 * The clone's config is the one copy. Nothing here is kept in the database:
 * the write path asks the clone each time, and [clones] reads the configs
 * again whenever a vault's row changes, which every sync does.
 */
@Singleton
class VaultClones
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val files: VaultFileStore,
        private val vaults: VaultDao,
        private val gate: VaultGate,
    ) : ReadOnlyRules {
        /** Counts the changes made here, which no row of the database records. */
        private val revision = MutableStateFlow(0)

        /** Every vault that is a clone, by id, kept current. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val clones: Flow<Map<Long, CloneSettings>> =
            combine(vaults.observe(), revision) { all, _ -> all }
                .mapLatest { all -> all.mapNotNull { vault -> read(vault)?.let { vault.id to it } }.toMap() }
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)

        override val readOnly: Flow<Set<Long>> =
            clones.map { all -> all.filterValues { it.readOnly }.keys }.distinctUntilChanged()

        override suspend fun isReadOnly(vaultId: Long): Boolean {
            val vault = vaults.byId(vaultId) ?: return false
            return withContext(Dispatchers.IO) { tree(vault)?.let(CloneConfig::isReadOnly) ?: false }
        }

        /**
         * Makes [vaultId] read-only, or not: that one key of the clone's
         * config and nothing else. What the clone already holds stays as it
         * is. Under the gate, because a sync asks the clone which it is more
         * than once, and the answer must not change half way through one.
         */
        suspend fun setReadOnly(
            vaultId: Long,
            readOnly: Boolean,
        ) = change(vaultId) { CloneConfig.setReadOnly(it, readOnly) }

        /**
         * Sets where [vaultId] pulls from and pushes to, and says whether it
         * did: an empty address is refused and the old one stays. Nothing is
         * fetched until the next sync, which is what says so when the new
         * address does not answer or holds another history.
         */
        suspend fun setAddress(
            vaultId: Long,
            address: String,
        ): Boolean = change(vaultId) { CloneConfig.setAddress(it, address) } ?: false

        private suspend fun <T> change(
            vaultId: Long,
            what: (File) -> T,
        ): T? =
            gate.withVault {
                val vault = vaults.byId(vaultId) ?: return@withVault null
                val done = withContext(Dispatchers.IO) { tree(vault)?.let(what) }
                revision.update { it + 1 }
                done
            }

        private fun read(vault: VaultEntity): CloneSettings? {
            val tree = tree(vault) ?: return null
            return runCatching { CloneSettings(CloneConfig.isReadOnly(tree), CloneConfig.address(tree)) }
                // A config that cannot be read is doubt, and in doubt the app does not write.
                .getOrDefault(CloneSettings(readOnly = true))
        }

        /** The vault's working tree when it is a clone, else null. */
        private fun tree(vault: VaultEntity): File? {
            if (SyncTransport.parse(vault.transport) != SyncTransport.SSH) return null
            // Before any JGit class reads a configuration.
            AndroidGitEnvironment.install(File(context.filesDir, "git"))
            return files.rootOf(vault.id).takeIf(CloneConfig::isRepository)
        }
    }
