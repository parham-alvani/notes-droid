package me.parham1995.notes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.parham1995.notes.sync.Author
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings, and a way back from a truncated one.
 *
 * Without a corruption handler a half-written preferences file throws on every
 * read, and the first read happens while the app is still starting -- so the
 * app cannot be opened at all, and the only way out is clearing its data,
 * which takes the synced vault, the token and the on-device SSH key with it.
 * A device that fills up mid-sync is exactly how the file ends up truncated.
 *
 * Losing the settings is a bad outcome; losing the vault to recover from
 * losing the settings is a worse one. So the file is replaced with an empty
 * one and everything here falls back to its default. The token lives in this
 * file too and goes with it, which is a token to paste again rather than a
 * sync to run again.
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** When images are fetched. Markdown is always synced. */
enum class ImagePolicy {
    /** Fetch an image the first time a note embeds it. The default. */
    ON_DEMAND,

    /** Pull every image during sync, but only on an unmetered network. */
    PREFETCH_ON_WIFI,

    /** Never fetch images; show a placeholder instead. */
    NEVER,
    ;

    companion object {
        fun parse(raw: String?): ImagePolicy = entries.firstOrNull { it.name == raw } ?: ON_DEMAND
    }
}

/**
 * How the vault is fetched.
 *
 * REST is the default and the light one: markdown only, and a quiet refresh
 * costs a single unbilled request. SSH authenticates with an on-device key
 * instead of a token that expires, but git cannot fetch a subset of paths, so
 * it is the full history and every attachment.
 */
enum class SyncTransport {
    REST,
    SSH,
    ;

    companion object {
        fun parse(raw: String?): SyncTransport = entries.firstOrNull { it.name == raw } ?: REST
    }
}

/** Where the app opens. */
enum class StartScreen {
    BROWSE,
    TASKS,
    SEARCH,
    ;

    companion object {
        fun parse(raw: String?): StartScreen = entries.firstOrNull { it.name == raw } ?: BROWSE
    }
}

/** How the browser orders what it lists. */
enum class BrowserSort {
    NAME,
    RECENTLY_OPENED,
    RECENTLY_CHANGED,
    ;

    companion object {
        fun parse(raw: String?): BrowserSort = entries.firstOrNull { it.name == raw } ?: NAME
    }
}

/**
 * Which palette to draw in.
 *
 * naz is a dark colourscheme and this app was built in it, so dark is the
 * default and the one the vault's own author reads in. Light exists because a
 * phone is read outdoors, and follows naz's accents rather than inventing a
 * second palette.
 */
enum class ThemeChoice {
    DARK,
    LIGHT,
    SYSTEM,
    ;

    companion object {
        fun parse(raw: String?): ThemeChoice = entries.firstOrNull { it.name == raw } ?: DARK
    }
}

/** How a note is set. */
data class ReadingSettings(
    /** Multiplies every text size in the reader. */
    val textScale: Float = 1f,
    /** Multiplies line height, for prose that runs long. */
    val lineSpacing: Float = 1f,
    val theme: ThemeChoice = ThemeChoice.DARK,
    /**
     * Set Persian in Vazirmatn rather than the platform's Naskh. On by
     * default, because that is why it is bundled.
     */
    val persianFont: Boolean = true,
    val startScreen: StartScreen = StartScreen.BROWSE,
    /**
     * Mark where the pen is hovering while reading.
     *
     * On by default on a device that has one, and invisible on a device that
     * does not: nothing is drawn until a stylus actually hovers.
     */
    val stylusSpotlight: Boolean = true,
    /**
     * Leave finished tasks out of a note.
     *
     * Off by default, because a note is what it says. On, a task file stops
     * being mostly a record of work already done -- this vault's own hold 2,900
     * ticked tasks against 460 open ones, so reading one on a phone is largely
     * scrolling past things that no longer need doing.
     */
    val hideCompletedTasks: Boolean = false,
    val browserSort: BrowserSort = BrowserSort.NAME,
) {
    companion object {
        val TEXT_SCALES = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)
        val LINE_SPACINGS = listOf(1f, 1.15f, 1.35f, 1.6f)
    }
}

/**
 * What the app needs in order to write, as opposed to read.
 *
 * Kept apart from the rest because writing is opt-in twice over: the credential
 * has to allow it, and an author has to be named. Neither has a sensible
 * default -- a commit attributed to the app rather than to the person who made
 * it is worse than no commit -- so until both are set, every write affordance
 * stays hidden.
 */
data class WriteSettings(
    val authorName: String = "",
    val authorEmail: String = "",
    /** Where a captured thought lands, relative to the vault's root. */
    val scratchpadPath: String = DEFAULT_SCRATCHPAD,
    /** Which vault holds it. Zero means the one being read. */
    val scratchpadVaultId: Long = 0,
) {
    val author: Author get() = Author(authorName.trim(), authorEmail.trim())

    /** Whether an author has been named well enough to commit as. */
    val hasAuthor: Boolean get() = author.isUsable

    companion object {
        const val DEFAULT_SCRATCHPAD = "Scratchpad.md"
    }
}

/** Which repository to read and how. Nothing about the vault is compiled in. */
data class VaultSettings(
    val owner: String = "",
    val repo: String = "",
    /** Null means "whatever the repository's default branch is". */
    val branch: String? = null,
    val imagePolicy: ImagePolicy = ImagePolicy.ON_DEMAND,
    val transport: SyncTransport = SyncTransport.REST,
    /**
     * Reach GitHub's SSH over 443 instead of 22. Plenty of mobile networks
     * block 22 outright, where a clone simply hangs until it times out.
     */
    val sshOverPort443: Boolean = false,
    val syncOnWifiOnly: Boolean = false,
    /** Refresh on a schedule as well as on demand. */
    val backgroundSync: Boolean = true,
    /**
     * Hours between background refreshes. WorkManager will not go below
     * fifteen minutes, and a quiet refresh costs one request, so the floor
     * here is about politeness rather than capability.
     */
    val syncIntervalHours: Int = DEFAULT_INTERVAL_HOURS,
    /**
     * A once-a-day notification summarising what is overdue and what is due.
     *
     * Off by default. A reader that starts posting notifications on its own is
     * a reader nobody asked for, and this one only earns its place once the
     * vault has enough dated tasks in it to be worth summarising.
     */
    val taskDigest: Boolean = false,
    /** Local hour to post it, 0-23. */
    val taskDigestHour: Int = DEFAULT_DIGEST_HOUR,
    val reading: ReadingSettings = ReadingSettings(),
    /**
     * The vault being read. Zero means "whichever is first", which is what a
     * fresh install and a single-vault install both want.
     */
    val activeVaultId: Long = 0,
    val write: WriteSettings = WriteSettings(),
) {
    val isConfigured: Boolean get() = owner.isNotBlank() && repo.isNotBlank()

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 6
        val INTERVAL_CHOICES = listOf(1, 3, 6, 12, 24)

        const val DEFAULT_DIGEST_HOUR = 8
        val DIGEST_HOUR_CHOICES = listOf(7, 9, 12, 18, 21)
    }
}

@Singleton
class SettingsStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        val settings: Flow<VaultSettings> =
            context.settingsDataStore.data.map { preferences ->
                VaultSettings(
                    owner = preferences[OWNER].orEmpty(),
                    repo = preferences[REPO].orEmpty(),
                    branch = preferences[BRANCH]?.takeIf { it.isNotBlank() },
                    imagePolicy = ImagePolicy.parse(preferences[IMAGE_POLICY]),
                    transport = SyncTransport.parse(preferences[TRANSPORT]),
                    sshOverPort443 = preferences[SSH_443] ?: false,
                    syncOnWifiOnly = preferences[WIFI_ONLY] ?: false,
                    backgroundSync = preferences[BACKGROUND_SYNC] ?: true,
                    syncIntervalHours =
                        preferences[INTERVAL_HOURS] ?: VaultSettings.DEFAULT_INTERVAL_HOURS,
                    taskDigest = preferences[TASK_DIGEST] ?: false,
                    taskDigestHour = preferences[DIGEST_HOUR] ?: VaultSettings.DEFAULT_DIGEST_HOUR,
                    activeVaultId = preferences[ACTIVE_VAULT] ?: 0L,
                    write =
                        WriteSettings(
                            authorName = preferences[AUTHOR_NAME].orEmpty(),
                            authorEmail = preferences[AUTHOR_EMAIL].orEmpty(),
                            scratchpadPath =
                                preferences[SCRATCHPAD_PATH]?.takeIf { it.isNotBlank() }
                                    ?: WriteSettings.DEFAULT_SCRATCHPAD,
                            scratchpadVaultId = preferences[SCRATCHPAD_VAULT] ?: 0L,
                        ),
                    reading =
                        ReadingSettings(
                            textScale = preferences[TEXT_SCALE] ?: 1f,
                            lineSpacing = preferences[LINE_SPACING] ?: 1f,
                            theme = ThemeChoice.parse(preferences[THEME]),
                            persianFont = preferences[PERSIAN_FONT] ?: true,
                            stylusSpotlight = preferences[STYLUS_SPOTLIGHT] ?: true,
                            hideCompletedTasks = preferences[HIDE_DONE_TASKS] ?: false,
                            startScreen = StartScreen.parse(preferences[START_SCREEN]),
                            browserSort = BrowserSort.parse(preferences[BROWSER_SORT]),
                        ),
                )
            }

        suspend fun current(): VaultSettings = settings.first()

        suspend fun setRepository(
            owner: String,
            repo: String,
            branch: String?,
        ) {
            context.settingsDataStore.edit {
                it[OWNER] = owner.trim()
                it[REPO] = repo.trim()
                if (branch.isNullOrBlank()) it.remove(BRANCH) else it[BRANCH] = branch.trim()
            }
        }

        suspend fun setAuthor(
            name: String,
            email: String,
        ) {
            context.settingsDataStore.edit {
                it[AUTHOR_NAME] = name.trim()
                it[AUTHOR_EMAIL] = email.trim()
            }
        }

        suspend fun setScratchpad(
            path: String,
            vaultId: Long,
        ) {
            context.settingsDataStore.edit {
                it[SCRATCHPAD_PATH] = path.trim().trim('/')
                it[SCRATCHPAD_VAULT] = vaultId
            }
        }

        suspend fun setImagePolicy(policy: ImagePolicy) {
            context.settingsDataStore.edit { it[IMAGE_POLICY] = policy.name }
        }

        suspend fun setTransport(transport: SyncTransport) {
            context.settingsDataStore.edit { it[TRANSPORT] = transport.name }
        }

        suspend fun setSshOverPort443(enabled: Boolean) {
            context.settingsDataStore.edit { it[SSH_443] = enabled }
        }

        suspend fun setSyncOnWifiOnly(enabled: Boolean) {
            context.settingsDataStore.edit { it[WIFI_ONLY] = enabled }
        }

        suspend fun setBackgroundSync(enabled: Boolean) {
            context.settingsDataStore.edit { it[BACKGROUND_SYNC] = enabled }
        }

        suspend fun setSyncIntervalHours(hours: Int) {
            context.settingsDataStore.edit { it[INTERVAL_HOURS] = hours }
        }

        suspend fun setTaskDigest(enabled: Boolean) {
            context.settingsDataStore.edit { it[TASK_DIGEST] = enabled }
        }

        suspend fun setTaskDigestHour(hour: Int) {
            context.settingsDataStore.edit { it[DIGEST_HOUR] = hour }
        }

        suspend fun setActiveVault(id: Long) {
            context.settingsDataStore.edit { it[ACTIVE_VAULT] = id }
        }

        suspend fun setTextScale(scale: Float) {
            context.settingsDataStore.edit { it[TEXT_SCALE] = scale }
        }

        suspend fun setLineSpacing(spacing: Float) {
            context.settingsDataStore.edit { it[LINE_SPACING] = spacing }
        }

        suspend fun setTheme(theme: ThemeChoice) {
            context.settingsDataStore.edit { it[THEME] = theme.name }
        }

        suspend fun setPersianFont(enabled: Boolean) {
            context.settingsDataStore.edit { it[PERSIAN_FONT] = enabled }
        }

        suspend fun setStylusSpotlight(enabled: Boolean) {
            context.settingsDataStore.edit { it[STYLUS_SPOTLIGHT] = enabled }
        }

        suspend fun setHideCompletedTasks(enabled: Boolean) {
            context.settingsDataStore.edit { it[HIDE_DONE_TASKS] = enabled }
        }

        suspend fun setStartScreen(screen: StartScreen) {
            context.settingsDataStore.edit { it[START_SCREEN] = screen.name }
        }

        /**
         * The open tabs, as the reader wrote them down.
         *
         * Opaque here on purpose: what a tab is belongs to the reader, and
         * this only has to hand the same text back after a restart.
         */
        val openTabs: Flow<String> = context.settingsDataStore.data.map { it[OPEN_TABS].orEmpty() }

        suspend fun setOpenTabs(encoded: String) {
            context.settingsDataStore.edit {
                if (encoded.isEmpty()) it.remove(OPEN_TABS) else it[OPEN_TABS] = encoded
            }
        }

        suspend fun setBrowserSort(sort: BrowserSort) {
            context.settingsDataStore.edit { it[BROWSER_SORT] = sort.name }
        }

        private companion object {
            val OWNER = stringPreferencesKey("repo_owner")
            val REPO = stringPreferencesKey("repo_name")
            val BRANCH = stringPreferencesKey("repo_branch")
            val IMAGE_POLICY = stringPreferencesKey("image_policy")
            val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
            val TRANSPORT = stringPreferencesKey("sync_transport")
            val SSH_443 = booleanPreferencesKey("ssh_over_443")
            val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
            val INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
            val TASK_DIGEST = booleanPreferencesKey("task_digest")
            val DIGEST_HOUR = intPreferencesKey("task_digest_hour")
            val ACTIVE_VAULT = longPreferencesKey("active_vault")
            val TEXT_SCALE = floatPreferencesKey("reading_text_scale")
            val LINE_SPACING = floatPreferencesKey("reading_line_spacing")
            val THEME = stringPreferencesKey("reading_theme")
            val PERSIAN_FONT = booleanPreferencesKey("reading_persian_font")
            val STYLUS_SPOTLIGHT = booleanPreferencesKey("reading_stylus_spotlight")
            val START_SCREEN = stringPreferencesKey("start_screen")
            val BROWSER_SORT = stringPreferencesKey("browser_sort")
            val OPEN_TABS = stringPreferencesKey("open_tabs")
            val AUTHOR_NAME = stringPreferencesKey("git_author_name")
            val AUTHOR_EMAIL = stringPreferencesKey("git_author_email")
            val SCRATCHPAD_PATH = stringPreferencesKey("scratchpad_path")
            val SCRATCHPAD_VAULT = longPreferencesKey("scratchpad_vault")
            val HIDE_DONE_TASKS = booleanPreferencesKey("hide_completed_tasks")
        }
    }
