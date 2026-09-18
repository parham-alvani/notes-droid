package me.parham1995.notes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NotesApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // JGit and sshd log through slf4j; slf4j-simple sends that to stderr,
        // which Android routes to logcat. Debug level makes the SSH handshake
        // visible to `just logs` without touching release behaviour.
        if (BuildConfig.DEBUG) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
            System.setProperty("org.slf4j.simpleLogger.showThreadName", "false")
        }
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
