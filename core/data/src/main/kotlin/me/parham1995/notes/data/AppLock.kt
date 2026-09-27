package me.parham1995.notes.data

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the vault is open to whoever is holding the phone.
 *
 * Locked from the moment the process starts, and again once the app has been
 * out of sight for longer than [GRACE_MS] -- long enough to answer a message
 * and come back, not long enough to leave the phone on a table. Whether the
 * lock is wanted at all is a setting ([PrivacySettings.appLock]); this only
 * keeps the state, so asking it about a phone with the lock off answers
 * "locked" and the caller decides that does not matter.
 *
 * The clock is a parameter so the grace period can be tested without waiting
 * for it. It is [SystemClock.elapsedRealtime], never the wall clock: changing
 * the time in Settings must not unlock anything.
 */
@Singleton
class AppLock(
    private val now: () -> Long,
) {
    @Inject
    constructor() : this(SystemClock::elapsedRealtime)

    private val _unlocked = MutableStateFlow(false)

    /** True once the person has proved who they are, until the lock closes again. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** When the app went out of sight, or null while it is in front. */
    @Volatile private var hiddenAt: Long? = null

    fun unlock() {
        hiddenAt = null
        _unlocked.value = true
    }

    /** The app went into the background. */
    fun hidden() {
        if (_unlocked.value) hiddenAt = now()
    }

    /** The app came back. Locks if it was away too long. */
    fun shown() {
        val at = hiddenAt ?: return
        hiddenAt = null
        if (now() - at > GRACE_MS) _unlocked.value = false
    }

    /**
     * Whether what is behind the lock may be handed to someone else now -- the
     * file picker asks while the app itself is in the background, so the
     * grace period is counted here rather than waiting for [shown].
     */
    fun isOpen(): Boolean {
        if (!_unlocked.value) return false
        val at = hiddenAt ?: return true
        return now() - at <= GRACE_MS
    }

    companion object {
        const val GRACE_MS = 60_000L
    }
}
