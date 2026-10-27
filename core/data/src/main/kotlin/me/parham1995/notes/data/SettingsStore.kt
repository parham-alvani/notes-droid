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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.parham1995.notes.obsidian.WorkingHours
import me.parham1995.notes.sync.Author
import java.time.LocalTime
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

/**
 * Where the app opens, and where back from a tab's root lands.
 *
 * [HOME] is the default: it is the phone's page for no note open, as the Mac
 * shows one in a window with no tabs, and Obsidian's Beautitab tab before it.
 */
enum class StartScreen {
    HOME,
    BROWSE,
    TASKS,
    SEARCH,
    ;

    companion object {
        fun parse(raw: String?): StartScreen = entries.firstOrNull { it.name == raw } ?: HOME
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

/**
 * How long a line of a note may run.
 *
 * A phone held upright is narrower than all of these, so this only ever shows
 * on a tablet or in landscape -- where a note set edge to edge runs to 150
 * characters a line and the eye loses its place going back for the next one.
 */
enum class LineWidth(
    /** The widest the text column may be, or null for the whole window. */
    val maxDp: Int?,
) {
    NARROW(560),
    COMFORTABLE(680),
    FULL(null),
    ;

    companion object {
        fun parse(raw: String?): LineWidth = entries.firstOrNull { it.name == raw } ?: COMFORTABLE
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
    val startScreen: StartScreen = StartScreen.HOME,
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
    /**
     * Holding a finger on a word in a note opens the dictionary on it. The
     * word is still selected as well, so copying works as it did; off, a hold
     * only selects.
     */
    val holdToDefine: Boolean = true,
    val browserSort: BrowserSort = BrowserSort.NAME,
    val lineWidth: LineWidth = LineWidth.COMFORTABLE,
    /**
     * Hold the screen awake while a note is open.
     *
     * Off by default: it costs battery, and most notes are read in less time
     * than the screen takes to dim. For the ones that are followed rather than
     * read -- a recipe, a checklist, a page open beside a keyboard -- it is the
     * difference between a reader and a screen that keeps going dark.
     */
    val keepScreenOn: Boolean = false,
    /**
     * On a tablet or a phone on its side, a tap on the page hides the bars --
     * the note's own, the rail, the system's -- and the next tap brings them
     * back. On by default: on a wide window the bars are a frame around the
     * page, and the page is what is being read.
     */
    val tapForFullScreen: Boolean = true,
    /**
     * The tabs' rail folded away, on a window wide enough to have one.
     *
     * A tablet held for reading gives the rail a strip of the screen that the
     * list and the note beside it could use. A small button in the corner
     * brings it back, and the choice stays across launches.
     */
    val hideRail: Boolean = false,
    /**
     * The home page's picture: a file's name in the app's own storage
     * ([HomePictureStore]), or empty for none -- and then the Beautitab
     * plugin's own background, when that is a picture in the vault.
     */
    val homePicture: String = "",
    /**
     * Leave the files AI agents read -- `CLAUDE.md`, `AGENTS.md` and the
     * like ([me.parham1995.notes.obsidian.HiddenFiles.AGENT_FILES]) -- out
     * of the browser, search and every other list, in whichever folder they
     * are. On by default: they are instructions for the tooling that writes
     * the vault, and a vault written that way has one at every level.
     */
    val hideAgentFiles: Boolean = true,
    /**
     * List everything, the hidden files too: the agents' files and what the
     * vault's own "Excluded files" leaves out. Off by default. A hidden file
     * opens from a link either way; this is only whether lists offer it.
     */
    val showHiddenFiles: Boolean = false,
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

/**
 * The phone's own calendars: which of them the calendar screen shows, and
 * where their week notes are written.
 *
 * Off until a calendar is chosen, because the export puts other people's
 * meetings into a repository, and that is a thing a person asks for.
 */
data class CalendarSettings(
    /** The calendars shown and exported, by the provider's calendar ID. */
    val calendars: Set<Long> = emptySet(),
    /** Calendars whose week notes say "Busy" and nothing else. */
    val busyOnly: Set<Long> = emptySet(),
    /** The vault the week notes go to. Zero means they are not written. */
    val exportVaultId: Long = 0,
    /** The hour the working day starts, for the free time in the week notes. */
    val workStart: Int = WorkingHours.DEFAULT_START,
    /** The hour it ends. Always after [workStart]; the store refuses anything else. */
    val workEnd: Int = WorkingHours.DEFAULT_END,
) {
    val exports: Boolean get() = exportVaultId != 0L && calendars.isNotEmpty()

    val hours: WorkingHours get() = WorkingHours(LocalTime.of(workStart, 0), LocalTime.of(workEnd, 0))

    companion object {
        val START_CHOICES = listOf(7, 8, 9, 10, 11)
        val END_CHOICES = listOf(16, 17, 18, 19, 20, 21)
    }
}

/**
 * Who may read the vault on this phone.
 *
 * Off by default: a lock is a thing a person asks for, and one that appears
 * after an update asks for a fingerprint nobody expected to give.
 */
data class PrivacySettings(
    /** Ask for the fingerprint or the screen lock before showing anything. */
    val appLock: Boolean = false,
    /** While locked, other apps' file pickers do not see the vaults either. */
    val lockPicker: Boolean = true,
)

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
     * Once a day, two notifications: what is due today by name, and how many are overdue.
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
    val privacy: PrivacySettings = PrivacySettings(),
    val calendar: CalendarSettings = CalendarSettings(),
) {
    val isConfigured: Boolean get() = owner.isNotBlank() && repo.isNotBlank()

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 6
        val INTERVAL_CHOICES = listOf(1, 3, 6, 12, 24)

        const val DEFAULT_DIGEST_HOUR = 8
        val DIGEST_HOUR_CHOICES = listOf(7, 9, 12, 18, 21, 22)
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
                    calendar =
                        CalendarSettings(
                            calendars = preferences[CALENDARS].toIds(),
                            busyOnly = preferences[CALENDARS_BUSY].toIds(),
                            exportVaultId = preferences[CALENDAR_EXPORT_VAULT] ?: 0L,
                            workStart = preferences.workingDay().first,
                            workEnd = preferences.workingDay().second,
                        ),
                    privacy =
                        PrivacySettings(
                            appLock = preferences[APP_LOCK] ?: false,
                            lockPicker = preferences[LOCK_PICKER] ?: true,
                        ),
                    reading =
                        ReadingSettings(
                            textScale = preferences[TEXT_SCALE] ?: 1f,
                            lineSpacing = preferences[LINE_SPACING] ?: 1f,
                            theme = ThemeChoice.parse(preferences[THEME]),
                            persianFont = preferences[PERSIAN_FONT] ?: true,
                            stylusSpotlight = preferences[STYLUS_SPOTLIGHT] ?: true,
                            hideCompletedTasks = preferences[HIDE_DONE_TASKS] ?: false,
                            holdToDefine = preferences[HOLD_TO_DEFINE] ?: true,
                            startScreen = StartScreen.parse(preferences[START_SCREEN]),
                            browserSort = BrowserSort.parse(preferences[BROWSER_SORT]),
                            lineWidth = LineWidth.parse(preferences[LINE_WIDTH]),
                            keepScreenOn = preferences[KEEP_SCREEN_ON] ?: false,
                            tapForFullScreen = preferences[TAP_FULL_SCREEN] ?: true,
                            hideRail = preferences[HIDE_RAIL] ?: false,
                            homePicture = preferences[HOME_PICTURE].orEmpty(),
                            hideAgentFiles = preferences[HIDE_AGENT_FILES] ?: true,
                            showHiddenFiles = preferences[SHOW_HIDDEN_FILES] ?: false,
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

        /** Shows [id] on the calendar screen and in the week notes, or stops. */
        suspend fun setCalendarShown(
            id: Long,
            shown: Boolean,
        ) {
            context.settingsDataStore.edit {
                val ids = it[CALENDARS].toIds()
                it[CALENDARS] = (if (shown) ids + id else ids - id).fromIds()
            }
        }

        suspend fun setCalendarBusyOnly(
            id: Long,
            busyOnly: Boolean,
        ) {
            context.settingsDataStore.edit {
                val ids = it[CALENDARS_BUSY].toIds()
                it[CALENDARS_BUSY] = (if (busyOnly) ids + id else ids - id).fromIds()
            }
        }

        /** Zero stops the export. */
        suspend fun setCalendarExportVault(vaultId: Long) {
            context.settingsDataStore.edit { it[CALENDAR_EXPORT_VAULT] = vaultId }
        }

        /** The stored working day, or the default one if what is stored ends before it starts. */
        private fun Preferences.workingDay(): Pair<Int, Int> {
            val start = this[CALENDAR_WORK_START] ?: WorkingHours.DEFAULT_START
            val end = this[CALENDAR_WORK_END] ?: WorkingHours.DEFAULT_END
            return if (start < end) start to end else WorkingHours.DEFAULT_START to WorkingHours.DEFAULT_END
        }

        /** Ignored unless [start] is before [end]: a working day that ends before it starts has no free time to count. */
        suspend fun setCalendarWorkingHours(
            start: Int,
            end: Int,
        ) {
            if (start >= end) return
            context.settingsDataStore.edit {
                it[CALENDAR_WORK_START] = start
                it[CALENDAR_WORK_END] = end
            }
        }

        private fun Set<String>?.toIds(): Set<Long> = this?.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }.orEmpty()

        private fun Set<Long>.fromIds(): Set<String> = mapTo(mutableSetOf()) { it.toString() }

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

        suspend fun setLineWidth(width: LineWidth) {
            context.settingsDataStore.edit { it[LINE_WIDTH] = width.name }
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

        suspend fun setHoldToDefine(enabled: Boolean) {
            context.settingsDataStore.edit { it[HOLD_TO_DEFINE] = enabled }
        }

        suspend fun setKeepScreenOn(enabled: Boolean) {
            context.settingsDataStore.edit { it[KEEP_SCREEN_ON] = enabled }
        }

        suspend fun setTapForFullScreen(enabled: Boolean) {
            context.settingsDataStore.edit { it[TAP_FULL_SCREEN] = enabled }
        }

        suspend fun setHideAgentFiles(enabled: Boolean) {
            context.settingsDataStore.edit { it[HIDE_AGENT_FILES] = enabled }
        }

        suspend fun setShowHiddenFiles(enabled: Boolean) {
            context.settingsDataStore.edit { it[SHOW_HIDDEN_FILES] = enabled }
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

        /** The version whose changes were last shown; zero before anything was. */
        val seenVersion: Flow<Int> = context.settingsDataStore.data.map { it[SEEN_VERSION] ?: 0 }

        suspend fun setSeenVersion(versionCode: Int) {
            context.settingsDataStore.edit { it[SEEN_VERSION] = versionCode }
        }

        suspend fun setAppLock(enabled: Boolean) {
            context.settingsDataStore.edit { it[APP_LOCK] = enabled }
        }

        suspend fun setLockPicker(enabled: Boolean) {
            context.settingsDataStore.edit { it[LOCK_PICKER] = enabled }
        }

        suspend fun setBrowserSort(sort: BrowserSort) {
            context.settingsDataStore.edit { it[BROWSER_SORT] = sort.name }
        }

        suspend fun setHideRail(hidden: Boolean) {
            context.settingsDataStore.edit { it[HIDE_RAIL] = hidden }
        }

        /** Empty for none. Set by [HomePictureStore], which owns the file it names. */
        suspend fun setHomePicture(name: String) {
            context.settingsDataStore.edit {
                if (name.isEmpty()) it.remove(HOME_PICTURE) else it[HOME_PICTURE] = name
            }
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
            val LINE_WIDTH = stringPreferencesKey("reading_line_width")
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
            val HOLD_TO_DEFINE = booleanPreferencesKey("hold_to_define")
            val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
            val TAP_FULL_SCREEN = booleanPreferencesKey("reading_tap_full_screen")
            val HIDE_RAIL = booleanPreferencesKey("hide_rail")
            val HOME_PICTURE = stringPreferencesKey("home_picture")
            val HIDE_AGENT_FILES = booleanPreferencesKey("hide_agent_files")
            val SHOW_HIDDEN_FILES = booleanPreferencesKey("show_hidden_files")
            val APP_LOCK = booleanPreferencesKey("app_lock")
            val SEEN_VERSION = intPreferencesKey("seen_version")
            val LOCK_PICKER = booleanPreferencesKey("app_lock_picker")
            val CALENDARS = stringSetPreferencesKey("calendar_ids")
            val CALENDARS_BUSY = stringSetPreferencesKey("calendar_busy_ids")
            val CALENDAR_EXPORT_VAULT = longPreferencesKey("calendar_export_vault")
            val CALENDAR_WORK_START = intPreferencesKey("calendar_work_start")
            val CALENDAR_WORK_END = intPreferencesKey("calendar_work_end")
        }
    }
