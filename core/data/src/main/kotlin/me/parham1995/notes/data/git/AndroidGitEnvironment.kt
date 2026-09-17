package me.parham1995.notes.data.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.util.TimeZone

/**
 * Makes JGit usable on Android.
 *
 * JGit assumes a desktop: on its very first call it goes looking for
 * `$HOME/.gitconfig` and `/etc/gitconfig`. Android sets no usable `HOME` and
 * the paths it would guess are unwritable, so that first call throws before any
 * repository work begins. This points all three config files at the app's own
 * storage instead.
 *
 * [install] has to run before **any** JGit API is touched, which is why it is
 * idempotent and called from the transport's constructor rather than left to a
 * caller to remember.
 */
object AndroidGitEnvironment {
    @Volatile
    private var installed = false

    @Synchronized
    fun install(configDir: File) {
        if (installed) return
        configDir.mkdirs()
        SystemReader.setInstance(AndroidSystemReader(configDir))
        installed = true
    }

    private class AndroidSystemReader(
        private val configDir: File,
    ) : SystemReader() {
        override fun getHostname(): String = "android"

        // Deliberately blind to the process environment: JGit would otherwise
        // read HOME, XDG_CONFIG_HOME and GIT_* from whatever the OS happens to
        // set, none of which point anywhere writable here.
        override fun getenv(variable: String?): String? =
            when (variable) {
                "HOME" -> configDir.absolutePath
                else -> null
            }

        override fun getProperty(key: String?): String =
            when (key) {
                "user.home", "user.dir" -> configDir.absolutePath
                else -> key?.let { System.getProperty(it) }.orEmpty()
            }

        override fun openUserConfig(
            parent: Config?,
            fs: FS?,
        ): FileBasedConfig = FileBasedConfig(parent, File(configDir, "user.config"), fs)

        override fun openSystemConfig(
            parent: Config?,
            fs: FS?,
        ): FileBasedConfig = FileBasedConfig(parent, File(configDir, "system.config"), fs)

        override fun openJGitConfig(
            parent: Config?,
            fs: FS?,
        ): FileBasedConfig = FileBasedConfig(parent, File(configDir, "jgit.config"), fs)

        // Deprecated upstream in favour of the java.time clock, but still
        // abstract, so it has to be implemented.
        @Deprecated("JGit deprecated this in favour of getClock()")
        override fun getCurrentTime(): Long = System.currentTimeMillis()

        @Deprecated("JGit deprecated this in favour of getTimeZoneAt()")
        override fun getTimezone(whenMillis: Long): Int =
            TimeZone.getDefault().getOffset(whenMillis) / MILLIS_PER_MINUTE

        private companion object {
            const val MILLIS_PER_MINUTE = 60_000
        }
    }
}
