package me.parham1995.notes.data

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.sync.GitHubException
import org.junit.Test
import java.time.Duration

/** How long the worker waits when GitHub has said when to come back. */
class SyncWorkerWaitTest {
    private val now = 1_800_000_000L

    @Test
    fun `an exhausted limit waits until it resets, not for WorkManager's backoff`() {
        val wait = SyncWorker.waitFor(GitHubException.RateLimited(resetEpochSeconds = now + 3_000), now)

        assertThat(wait).isAtLeast(Duration.ofSeconds(3_000))
        assertThat(wait).isAtMost(Duration.ofSeconds(3_100))
    }

    @Test
    fun `a secondary limit waits as long as it asked`() {
        val wait = SyncWorker.waitFor(GitHubException.SlowDown(retryAfterSeconds = 120), now)

        assertThat(wait).isAtLeast(Duration.ofSeconds(120))
    }

    @Test
    fun `a reset already past waits only a moment`() {
        val wait = SyncWorker.waitFor(GitHubException.RateLimited(resetEpochSeconds = now - 60), now)

        assertThat(wait).isAtMost(Duration.ofMinutes(1))
    }

    @Test
    fun `anything else is left to the ordinary retry`() {
        assertThat(SyncWorker.waitFor(GitHubException.Unexpected(500, "oops"), now)).isNull()
        assertThat(SyncWorker.waitFor(GitHubException.RateLimited(resetEpochSeconds = 0), now)).isNull()
    }
}
