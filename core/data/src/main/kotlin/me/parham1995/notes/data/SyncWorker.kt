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
import me.parham1995.notes.sync.GitHubException

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
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            createChannel()
            return try {
                setForeground(foregroundInfo(0, 0))
                val plan =
                    repository.sync { done, total ->
                        setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                    }
                Result.success(
                    workDataOf(
                        KEY_ADDED to plan.adds.size,
                        KEY_MODIFIED to plan.modifies.size,
                        KEY_RENAMED to plan.renames.size,
                        KEY_DELETED to plan.deletes.size,
                    ),
                )
            } catch (failure: NotConfiguredException) {
                // Nothing to retry: the app has not been set up yet.
                Result.failure(errorData(failure.message))
            } catch (failure: GitHubException.Unauthorized) {
                // Retrying cannot fix a rejected token; the user must replace it.
                Result.failure(errorData(TOKEN_REJECTED))
            } catch (failure: GitHubException.NotFound) {
                Result.failure(errorData(failure.message))
            } catch (failure: Exception) {
                // Rate limits, connectivity, a half-finished download: all worth
                // another go, and the manifest makes resuming free. But not
                // forever -- an unbounded retry backs off into the distance
                // while the UI still calls it "syncing", which is
                // indistinguishable from a hang.
                if (runAttemptCount >= MAX_ATTEMPTS) {
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
            const val UNIQUE_WORK = "vault-sync"
            const val CHANNEL_ID = "vault-sync"
            const val NOTIFICATION_ID = 1

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
        }
    }
