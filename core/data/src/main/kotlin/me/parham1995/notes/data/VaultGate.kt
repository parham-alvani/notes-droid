package me.parham1995.notes.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One thing touches a vault's files at a time.
 *
 * A sync and a write are both allowed to run whenever they like -- one is
 * scheduled by WorkManager, the other happens when somebody taps a checkbox --
 * and for an SSH vault they drive the *same* directory: the sync is checking a
 * tree out while the write is committing from it. Nothing serialised them, so
 * an edit made while a background refresh happened to be running could commit a
 * half-written tree, or lose itself in the checkout. Not a race that needs bad
 * luck, either; a six-hourly refresh and a phone used in the evening will find
 * it on their own.
 *
 * One lock for all vaults rather than one each. Syncs already run their
 * repositories in sequence, writes are single edits, and neither is long enough
 * for the contention to matter -- where a second lock would only add a way to
 * deadlock.
 */
@Singleton
class VaultGate
    @Inject
    constructor() {
        private val mutex = Mutex()

        /**
         * Runs [block] with the vault to itself.
         *
         * **Not reentrant.** Anything called from inside a held gate must be the
         * unlocked form -- see [VaultWriteRepository.drain], which exists for
         * exactly that reason.
         */
        suspend fun <T> withVault(block: suspend () -> T): T = mutex.withLock { block() }

        /** Whether something holds it, for a status line rather than a decision. */
        val busy: Boolean get() = mutex.isLocked
    }
