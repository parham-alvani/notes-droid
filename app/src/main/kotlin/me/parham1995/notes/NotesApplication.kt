package me.parham1995.notes

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.parham1995.notes.data.CrashLog
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.widget.RecentNotesWidget
import me.parham1995.notes.widget.TasksWidget
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class NotesApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var crashLog: Provider<CrashLog>

    // Providers rather than the objects themselves: both reach WorkManager,
    // and Hilt fills these in during super.onCreate(), which is before this
    // class has finished becoming the Configuration.Provider WorkManager will
    // ask for.
    @Inject
    lateinit var settings: Provider<SettingsStore>

    @Inject
    lateinit var scheduler: Provider<SyncScheduler>

    /**
     * Startup scheduling runs here, and a failure in it must not be fatal.
     *
     * Both collectors below read settings, which touches the filesystem, and
     * both reach WorkManager. Without this handler anything either of them
     * throws is an uncaught exception on a background thread before a single
     * screen exists -- so the app dies on launch, every launch, over work that
     * is only ever about when the *next* refresh happens. Recording it and
     * carrying on leaves an app that opens and a reason in the journal.
     */
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, failure ->
                    Log.e(TAG, "startup scheduling failed", failure)
                },
        )

    override fun onCreate() {
        super.onCreate()
        // Before anything else that could throw. It records and delegates; it
        // does not recover.
        crashLog.get().install()
        // JGit and sshd log through slf4j; slf4j-simple sends that to stderr,
        // which Android routes to logcat. Debug level makes the SSH handshake
        // visible to `just logs` without touching release behaviour.
        if (BuildConfig.DEBUG) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
            System.setProperty("org.slf4j.simpleLogger.showThreadName", "false")
        }
        scheduleBackgroundSync()
        scheduleTaskDigest()
        SyncWorker.afterSync = {
            TasksWidget.refresh(this)
            RecentNotesWidget.refresh(this)
        }
    }

    /**
     * Keeps the scheduled refresh in step with the settings.
     *
     * This is where background sync was missing entirely: the scheduler could
     * always register periodic work and nothing ever asked it to, so the vault
     * only ever refreshed when someone tapped. Registering from here rather
     * than from a screen means it survives the app being opened once and never
     * visited again, which is the case that matters.
     *
     * `enqueueUniquePeriodicWork` with UPDATE is idempotent, so running this on
     * every launch costs nothing and repairs a schedule the system dropped.
     */
    private fun scheduleBackgroundSync() {
        scope.launch {
            settings
                .get()
                .settings
                .map { Triple(it.backgroundSync && it.isConfigured, it.syncIntervalHours, it.syncOnWifiOnly) }
                .distinctUntilChanged()
                .collect { (enabled, hours, wifiOnly) ->
                    if (enabled) {
                        scheduler.get().schedulePeriodic(wifiOnly, hours.toLong())
                    } else {
                        scheduler.get().cancelPeriodic()
                    }
                }
        }
    }

    /**
     * Arms the daily digest, and re-anchors it to the chosen hour on every
     * launch -- periodic work drifts, and opening the app is the cheapest
     * moment to put it back where it belongs.
     */
    private fun scheduleTaskDigest() {
        scope.launch {
            settings
                .get()
                .settings
                .map { it.taskDigest to it.taskDigestHour }
                .distinctUntilChanged()
                .collect { (enabled, hour) ->
                    if (enabled) scheduler.get().scheduleDigest(hour) else scheduler.get().cancelDigest()
                }
        }
    }

    private companion object {
        const val TAG = "Daftar"
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
