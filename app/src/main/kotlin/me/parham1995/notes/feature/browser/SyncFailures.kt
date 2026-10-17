package me.parham1995.notes.feature.browser

import androidx.work.WorkInfo
import me.parham1995.notes.R
import me.parham1995.notes.data.NetworkNow
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.ui.UiText

/** How a run of the sync worker ended, once it has. */
sealed interface AttemptEnd {
    data object Succeeded : AttemptEnd

    /** [workError] is what the worker gave up with; null on a retry. */
    data class Failed(
        val workError: String?,
    ) : AttemptEnd

    /**
     * Stopped by the system rather than failed: the network it was using
     * went, or the job's quota ran out. WorkManager runs it again once it
     * can, and that run's end is the one worth reporting.
     */
    data object Interrupted : AttemptEnd
}

/**
 * Follows the sync's work and says when a run that was going has stopped.
 *
 * A failed run is retried with backoff, and between tries the job is
 * ENQUEUED, not FAILED -- for minutes, before WorkManager gives up. Waiting for
 * FAILED meant a pull-to-refresh that hit no signal put the spinner away and
 * said nothing at all, so the end of each run is what is watched here.
 */
class AttemptWatch {
    private var wasRunning = false

    /** The end of the run that was going, or null if none just ended. */
    fun next(infos: List<WorkInfo>): AttemptEnd? {
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val ended = wasRunning && !running
        wasRunning = running
        if (!ended) return null
        return when {
            infos.any { it.state == WorkInfo.State.SUCCEEDED } -> AttemptEnd.Succeeded
            infos.any { it.state == WorkInfo.State.FAILED } ->
                AttemptEnd.Failed(
                    infos.first { it.state == WorkInfo.State.FAILED }.outputData.getString(SyncWorker.KEY_ERROR),
                )
            // Back in the queue having run: either it failed and will try
            // again, or the system stopped it -- Wi-Fi dropped to mobile data
            // mid-fetch -- and it will simply run again. Only the first is a
            // failure; the second was reported as one, and the retry then
            // succeeded in silence.
            infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } -> {
                val requeued = infos.first { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
                if (requeued.stopReason == WorkInfo.STOP_REASON_NOT_STOPPED) {
                    AttemptEnd.Failed(null)
                } else {
                    AttemptEnd.Interrupted
                }
            }
            // Cancelled, or replaced by a newer sync: nothing to report.
            else -> null
        }
    }
}

/**
 * Why a sync failed, in a line.
 *
 * A rejected token comes first and alone: every vault goes on failing with it
 * until it is replaced, and the host's own words ("Bad credentials") do not
 * say so. Otherwise each vault that failed says why, named when there is more
 * than one, and [workError] covers a failure no vault recorded.
 */
internal fun syncFailureMessage(
    vaults: List<VaultEntity>,
    workError: String?,
): UiText {
    if (workError == SyncWorker.TOKEN_REJECTED) return UiText.Resource(R.string.help_token_rejected)
    val enabled = vaults.filter { it.enabled }
    val reasons =
        enabled.mapNotNull { vault ->
            vault.lastError?.let { if (enabled.size > 1) "${vault.label}: $it" else it }
        }
    val reason = reasons.joinToString("; ").ifEmpty { workError }
    return if (reason.isNullOrEmpty()) {
        UiText.Resource(R.string.note_sync_failed)
    } else {
        UiText.Resource(R.string.sync_failed_because, listOf(reason))
    }
}

/**
 * Why a sync just asked for will not start yet, or null if it will.
 *
 * WorkManager waits silently for the network the sync needs, so without this
 * a pull on Wi-Fi with no internet put the spinner away and nothing happened,
 * then or later, until the network came back.
 */
internal fun syncWaitMessage(
    network: NetworkNow,
    wifiOnly: Boolean,
): UiText? =
    when {
        wifiOnly && !network.unmetered -> UiText.Resource(R.string.sync_waiting_wifi)
        !network.validated -> UiText.Resource(R.string.sync_waiting_internet)
        else -> null
    }
