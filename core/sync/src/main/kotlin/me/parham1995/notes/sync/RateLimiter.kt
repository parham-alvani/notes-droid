package me.parham1995.notes.sync

import kotlinx.coroutines.delay

/**
 * Keeps request pacing under GitHub's secondary limit.
 *
 * The primary limit (5,000/hour for a fine-grained token) is not the binding
 * constraint -- a first sync of a few thousand notes fits inside it. The
 * secondary limit is: roughly 900 points per minute, and it is enforced by
 * refusing requests rather than queueing them. So requests are spaced out, and
 * the headers on every response are used to slow down before being told to.
 */
class RateLimiter(
    private val permitsPerSecond: Int = DEFAULT_PERMITS_PER_SECOND,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Guards [nextAvailable]. A plain lock rather than a coroutine mutex:
     * nothing suspends while it is held, and [observe] runs on OkHttp's own
     * threads, where it used to write the field with no lock at all while a
     * download on another thread was reading it.
     */
    private val lock = Any()
    private val intervalNanos = NANOS_PER_SECOND / permitsPerSecond
    private var nextAvailable = 0L

    /** Suspends until it is this caller's turn to issue a request. */
    suspend fun acquire() {
        val waitNanos =
            synchronized(lock) {
                val now = nanoTime()
                val scheduled = maxOf(now, nextAvailable)
                nextAvailable = scheduled + intervalNanos
                scheduled - now
            }
        if (waitNanos > 0) sleep(waitNanos / NANOS_PER_MILLI)
    }

    /**
     * Feeds a response's rate-limit headers back in. When the remaining budget
     * gets low the pace is stretched to cover the window that is left, which
     * avoids hitting the wall mid-sync and having to unwind.
     */
    fun observe(response: okhttp3.Response) {
        val remaining = response.header("x-ratelimit-remaining")?.toLongOrNull() ?: return
        if (remaining > LOW_WATER_MARK) return
        val reset = response.header("x-ratelimit-reset")?.toLongOrNull() ?: return
        val secondsLeft = reset - System.currentTimeMillis() / MILLIS_PER_SECOND
        if (secondsLeft <= 0 || remaining <= 0) return
        val spacing = secondsLeft * NANOS_PER_SECOND / remaining
        synchronized(lock) { nextAvailable = maxOf(nextAvailable, nanoTime() + spacing) }
    }

    private companion object {
        const val DEFAULT_PERMITS_PER_SECOND = 10
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1000L
        const val LOW_WATER_MARK = 100L
    }
}
