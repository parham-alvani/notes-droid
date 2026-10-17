package me.parham1995.notes.feature.browser

import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.R
import me.parham1995.notes.data.NetworkNow
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.ui.UiText
import org.junit.Test
import java.util.UUID

class SyncFailuresTest {
    private val id = UUID.randomUUID()

    private fun work(
        state: WorkInfo.State,
        attempts: Int = 0,
        error: String? = null,
        stopReason: Int = WorkInfo.STOP_REASON_NOT_STOPPED,
    ) = WorkInfo(
        id = id,
        state = state,
        tags = emptySet(),
        outputData = error?.let { workDataOf(SyncWorker.KEY_ERROR to it) } ?: workDataOf(),
        runAttemptCount = attempts,
        stopReason = stopReason,
    )

    private fun vault(
        name: String,
        error: String? = null,
        enabled: Boolean = true,
    ) = VaultEntity(owner = "o", repo = name, name = name, lastError = error, enabled = enabled)

    @Test
    fun `a run that goes back in the queue has failed, though the job has not`() {
        val watch = AttemptWatch()

        assertThat(watch.next(listOf(work(WorkInfo.State.ENQUEUED)))).isNull()
        assertThat(watch.next(listOf(work(WorkInfo.State.RUNNING)))).isNull()
        assertThat(watch.next(listOf(work(WorkInfo.State.ENQUEUED, attempts = 1))))
            .isEqualTo(AttemptEnd.Failed(null))
    }

    @Test
    fun `a run the system stopped is not a failure`() {
        // Seen on a phone: Wi-Fi went mid-fetch, the job was stopped for its
        // network, and "Sync failed" was said of a sync that then ran again
        // and succeeded.
        val watch = AttemptWatch()
        watch.next(listOf(work(WorkInfo.State.RUNNING)))

        assertThat(
            watch.next(
                listOf(
                    work(
                        WorkInfo.State.ENQUEUED,
                        attempts = 1,
                        stopReason = WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
                    ),
                ),
            ),
        ).isEqualTo(AttemptEnd.Interrupted)
    }

    @Test
    fun `no internet says the sync is waiting for it`() {
        assertThat(syncWaitMessage(NetworkNow(validated = false, unmetered = true), wifiOnly = false))
            .isEqualTo(UiText.Resource(R.string.sync_waiting_internet))
    }

    @Test
    fun `wifi only on mobile data says it is waiting for wifi`() {
        assertThat(syncWaitMessage(NetworkNow(validated = true, unmetered = false), wifiOnly = true))
            .isEqualTo(UiText.Resource(R.string.sync_waiting_wifi))
    }

    @Test
    fun `a network that reaches the internet has nothing to wait for`() {
        assertThat(syncWaitMessage(NetworkNow(validated = true, unmetered = false), wifiOnly = false)).isNull()
        assertThat(syncWaitMessage(NetworkNow(validated = true, unmetered = true), wifiOnly = true)).isNull()
    }

    @Test
    fun `a run that gives up carries the worker's reason`() {
        val watch = AttemptWatch()
        watch.next(listOf(work(WorkInfo.State.RUNNING, attempts = 3)))

        assertThat(watch.next(listOf(work(WorkInfo.State.FAILED, attempts = 4, error = "gave up"))))
            .isEqualTo(AttemptEnd.Failed("gave up"))
    }

    @Test
    fun `success and cancellation are not failures`() {
        val watch = AttemptWatch()
        watch.next(listOf(work(WorkInfo.State.RUNNING)))
        assertThat(watch.next(listOf(work(WorkInfo.State.SUCCEEDED)))).isEqualTo(AttemptEnd.Succeeded)

        watch.next(listOf(work(WorkInfo.State.RUNNING)))
        assertThat(watch.next(listOf(work(WorkInfo.State.CANCELLED)))).isNull()
    }

    @Test
    fun `a failure that was never running is not this pull's`() {
        assertThat(AttemptWatch().next(listOf(work(WorkInfo.State.FAILED, error = "old")))).isNull()
    }

    @Test
    fun `one vault's reason is said as it is`() {
        assertThat(syncFailureMessage(listOf(vault("notes", error = "timeout")), workError = "gave up"))
            .isEqualTo(UiText.Resource(R.string.sync_failed_because, listOf("timeout")))
    }

    @Test
    fun `with several vaults the failing ones are named, and one switched off is not`() {
        val message =
            syncFailureMessage(
                listOf(vault("notes", error = "timeout"), vault("work"), vault("old", error = "gone", enabled = false)),
                workError = null,
            )

        assertThat(message).isEqualTo(UiText.Resource(R.string.sync_failed_because, listOf("notes: timeout")))
    }

    @Test
    fun `a rejected token says what to do about it`() {
        assertThat(syncFailureMessage(listOf(vault("notes", error = "Bad credentials")), SyncWorker.TOKEN_REJECTED))
            .isEqualTo(UiText.Resource(R.string.help_token_rejected))
    }

    @Test
    fun `the worker's reason covers what no vault recorded`() {
        assertThat(syncFailureMessage(listOf(vault("notes")), workError = "no repository configured"))
            .isEqualTo(UiText.Resource(R.string.sync_failed_because, listOf("no repository configured")))
        assertThat(syncFailureMessage(listOf(vault("notes")), workError = null))
            .isEqualTo(UiText.Resource(R.string.note_sync_failed))
    }
}
