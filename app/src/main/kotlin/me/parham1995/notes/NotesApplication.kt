package me.parham1995.notes

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.parham1995.notes.data.CrashLog
import me.parham1995.notes.data.HiddenFileStore
import me.parham1995.notes.data.PinStore
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.reminder.Reminders
import me.parham1995.notes.ui.image.VaultAnimated
import me.parham1995.notes.ui.image.VaultSvg
import me.parham1995.notes.widget.Widgets
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class NotesApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
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

    @Inject
    lateinit var pins: Provider<PinStore>

    @Inject
    lateinit var vaults: Provider<VaultRepository>

    @Inject
    lateinit var reminders: Provider<Reminders>

    @Inject
    lateinit var hiddenFiles: Provider<HiddenFileStore>

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
        watchCalendar()
        SyncWorker.afterSync = ::refreshWidgets
        // A task ticked in the app leaves the home screen a tick behind
        // otherwise, until whenever the next background refresh happens to run.
        VaultWriteRepository.afterWrite = ::refreshWidgets
        refreshPinsWhenTheyChange()
        keepHiddenFilesCurrent()
        // A force-stop forgets every alarm and leaves no broadcast to say so;
        // the next launch is the first chance to set them again.
        reminders.get().rearm(this)
    }

    /**
     * Redraws every placed widget. Called from a worker's thread and a
     * repository's, neither of which is a coroutine; the scope's handler
     * records anything Glance throws rather than letting it take the app down.
     */
    private fun refreshWidgets() {
        scope.launch { Widgets.refreshAll(this@NotesApplication) }
    }

    /**
     * Redraws the pinned notes widget when a note is pinned or unpinned, or
     * the vault being read changes -- the two things it shows that a sync
     * does not move.
     *
     * The first value is the state at launch, which the widget already drew,
     * so it is skipped rather than answered with a redraw every time the
     * process starts.
     */
    private fun refreshPinsWhenTheyChange() {
        scope.launch {
            combine(pins.get().pins, vaults.get().activeVaultId) { all, active -> all to active }
                .distinctUntilChanged()
                .drop(1)
                .collect { Widgets.refreshPinned(this@NotesApplication) }
        }
    }

    /**
     * Marks which notes the lists leave out, on every launch and again
     * whenever "hide the agents' files" or "show hidden files" is switched.
     *
     * On launch as well, rather than only on a change: the migration that
     * added the mark left every note shown, and a note the rules hide has to
     * be marked before the first sync after an upgrade would get to it. A
     * vault where every mark is already right costs one read of its paths.
     * Widgets show recent notes and tasks, so they are redrawn when a mark
     * moved.
     */
    private fun keepHiddenFilesCurrent() {
        scope.launch {
            settings
                .get()
                .settings
                .map { it.reading.hideAgentFiles to it.reading.showHiddenFiles }
                .distinctUntilChanged()
                .collect {
                    if (hiddenFiles.get().applyAll() > 0) Widgets.refreshAll(this@NotesApplication)
                }
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

    /**
     * Keeps the calendar watch in step with the export setting: armed while
     * week notes are written, cancelled when they are not. KEEP on the first
     * value, which is every launch, so opening the app does not cancel a run
     * under way; REPLACE after that, because only a change of the Wi-Fi rule
     * changes what the watch waits for.
     */
    private fun watchCalendar() {
        scope.launch {
            var first = true
            settings
                .get()
                .settings
                .map { it.calendar.exports to it.syncOnWifiOnly }
                .distinctUntilChanged()
                .collect { (exports, wifiOnly) ->
                    when {
                        !exports -> scheduler.get().stopWatchingCalendar()
                        first -> scheduler.get().watchCalendar(wifiOnly)
                        else -> scheduler.get().watchCalendar(wifiOnly, ExistingWorkPolicy.REPLACE)
                    }
                    first = false
                }
        }
    }

    private companion object {
        const val TAG = "Daftar"
    }

    /**
     * Coil's defaults, plus the SVG decoder that reads the vault's figures and
     * the one that keeps an animated picture still when the system's
     * animations are off. Components added here are tried before the ones Coil
     * finds by itself, so each takes over from the one its library installs.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(VaultSvg.factory)
                add(VaultAnimated.factory)
            }.build()

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
