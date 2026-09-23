package me.parham1995.notes.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import me.parham1995.notes.sync.GitHubException
import java.time.Duration

/**
 * Runs a sync outside the UI.
 *
 * A first sync is minutes long, so it cannot live in a ViewModel coroutine --
 * Android kills a backgrounded app and the work would die with it. As a
 * foreground worker with a progress notification it survives, which is also why
 * the manifest declares FOREGROUND_SERVICE_DATA_SYNC and POST_NOTIFICATIONS.
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted params: WorkerParameters,
        private val repository: SyncRepository,
        private val log: SyncLog,
        private val scheduler: SyncScheduler,
        private val settings: SettingsStore,
    ) : CoroutineWorker(context, params) {
        /**
         * Run after a successful sync, for anything outside the app that shows
         * the vault. Set by the application rather than injected, because this
         * module deliberately knows nothing about the UI or its widgets.
         */
        private val onSynced: (() -> Unit)? get() = afterSync

        override suspend fun doWork(): Result {
            // First thing, before anything that can fail: if the worker starts
            // at all, the journal says so.
            log.info("worker started (attempt ${runAttemptCount + 1})")

            runCatching { createChannel() }
                .onFailure { log.warn("notification channel unavailable: ${it.message}") }

            // A foreground service is for the sync somebody is waiting on, and
            // only that one. A scheduled refresh is ordinary background work
            // that Android runs happily as a job -- and an app in the
            // background is not permitted to start a foreground service, so
            // asking anyway threw ForegroundServiceStartNotAllowedException on
            // every scheduled run. It was caught and the sync continued, but it
            // filled the journal with a warning about a thing that was never
            // needed.
            //
            // Still not fatal when it does fail. This used to be the first
            // statement in the try, so a denied notification permission threw
            // before the sync began, was caught as a generic failure, and
            // retried forever -- the sync never ran and never logged a thing,
            // which is indistinguishable from a hang.
            val userInitiated = inputData.getBoolean(KEY_USER_INITIATED, false)
            val foreground =
                userInitiated &&
                    runCatchingUnlessCancelled { setForeground(foregroundInfo(0, 0)) }
                        .onFailure {
                            log.warn(
                                "cannot run in the foreground (${it::class.simpleName}): " +
                                    "syncing anyway, but Android may kill it if you leave the app",
                            )
                        }.isSuccess

            return try {
                val plan =
                    repository.sync { done, total ->
                        runCatching {
                            setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                        }
                    }
                log.info(if (foreground) "ran in the foreground" else "ran as a background job")
                // Anything on the home screen is showing the previous sync's
                // answer until something tells it otherwise.
                runCatching { onSynced?.invoke() }
                Result.success(
                    workDataOf(
                        KEY_ADDED to plan.adds.size,
                        KEY_MODIFIED to plan.modifies.size,
                        KEY_RENAMED to plan.renames.size,
                        KEY_DELETED to plan.deletes.size,
                    ),
                )
            } catch (cancelled: CancellationException) {
                // WorkManager stopped this -- Cancel was pressed, a newer
                // sync replaced it, or the constraints no longer hold. Not a
                // failure to retry: returning one here would count an
                // attempt and schedule another run of the work just stopped.
                log.info("sync cancelled")
                throw cancelled
            } catch (failure: NotConfiguredException) {
                // Nothing to retry: the app has not been set up yet.
                log.error("not configured: ${failure.message}")
                Result.failure(errorData(failure.message))
            } catch (failure: GitHubException.Unauthorized) {
                // Retrying cannot fix a rejected token; the user must replace it.
                log.error("the access token was rejected")
                Result.failure(errorData(TOKEN_REJECTED))
            } catch (failure: GitHubException.NotFound) {
                log.error("not found: ${failure.message}")
                Result.failure(errorData(failure.message))
            } catch (failure: GitHubException) {
                // GitHub said when to come back. WorkManager's own backoff
                // knows nothing of that: it retried within minutes into an
                // hour-long limit, spent every attempt, and gave up.
                val wait = waitFor(failure, System.currentTimeMillis() / MILLIS_PER_SECOND)
                if (wait != null) {
                    scheduler.syncAfter(wait, settings.current().syncOnWifiOnly)
                    val minutes = wait.toMinutes().coerceAtLeast(1)
                    log.warn("GitHub asked to wait - syncing again in $minutes min")
                    Result.failure(errorData("GitHub's rate limit - syncing again in $minutes min"))
                } else {
                    retryOrGiveUp(failure)
                }
            } catch (failure: Exception) {
                // Rate limits, connectivity, a half-finished download: all worth
                // another go, and the manifest makes resuming free. But not
                // forever -- an unbounded retry backs off into the distance
                // while the UI still calls it "syncing", which is
                // indistinguishable from a hang.
                retryOrGiveUp(failure)
            }
        }

        private suspend fun retryOrGiveUp(failure: Exception): Result {
            log.error(failure.describeChain())
            return if (runAttemptCount >= MAX_ATTEMPTS) {
                Result.failure(
                    errorData(
                        "gave up after $MAX_ATTEMPTS attempts - " +
                            "${failure::class.simpleName}: ${failure.message}",
                    ),
                )
            } else {
                Result.retry()
            }
        }

        /**
         * Used by WorkManager itself for expedited work below API 31, where an
         * expedited job does not exist and it runs one as a foreground service.
         * Above that it is never called.
         */
        override suspend fun getForegroundInfo(): ForegroundInfo {
            runCatching { createChannel() }
            return foregroundInfo(0, 0)
        }

        private fun errorData(message: String?): Data = workDataOf(KEY_ERROR to (message ?: "sync failed"))

        private fun foregroundInfo(
            done: Int,
            total: Int,
        ): ForegroundInfo {
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setContentTitle("Syncing notes")
                    .setContentText(if (total > 0) "$done of $total" else "Checking for changes")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setProgress(total, done, total == 0)
                    .build()

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        private fun createChannel() {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Vault sync", NotificationManager.IMPORTANCE_LOW),
            )
        }

        companion object {
            /** Set once at startup; see [onSynced]. */
            @Volatile
            var afterSync: (() -> Unit)? = null

            const val UNIQUE_WORK = "vault-sync"
            const val CHANNEL_ID = "vault-sync"
            const val NOTIFICATION_ID = 1

            /**
             * Set when a person asked for this sync, as opposed to the
             * schedule doing it. Decides whether a foreground service is worth
             * asking for.
             */
            const val KEY_USER_INITIATED = "user_initiated"

            const val KEY_DONE = "done"
            const val KEY_TOTAL = "total"
            const val KEY_ERROR = "error"
            const val KEY_ADDED = "added"
            const val KEY_MODIFIED = "modified"
            const val KEY_RENAMED = "renamed"
            const val KEY_DELETED = "deleted"

            /** Past this the failure is reported instead of retried forever. */
            const val MAX_ATTEMPTS = 4

            /** Distinguishes an expired or revoked token from any other failure. */
            const val TOKEN_REJECTED = "token-rejected"

            private const val MILLIS_PER_SECOND = 1000L

            /** A little past the reset, so the first request is not early. */
            private const val MARGIN_SECONDS = 30L

            /**
             * How long GitHub asked to be left alone, or null when it did not
             * say. [nowEpochSeconds] is passed in so this can be tested.
             */
            fun waitFor(
                failure: GitHubException,
                nowEpochSeconds: Long,
            ): Duration? =
                when (failure) {
                    is GitHubException.RateLimited ->
                        failure.resetEpochSeconds
                            .takeIf { it > 0 }
                            ?.let { Duration.ofSeconds((it - nowEpochSeconds).coerceAtLeast(0) + MARGIN_SECONDS) }
                    is GitHubException.SlowDown -> Duration.ofSeconds(failure.retryAfterSeconds + MARGIN_SECONDS)
                    else -> null
                }
        }
    }
