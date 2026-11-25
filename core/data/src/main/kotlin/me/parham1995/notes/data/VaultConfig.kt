package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.obsidian.DaftarConfig
import me.parham1995.notes.obsidian.HiddenFiles
import me.parham1995.notes.obsidian.ResolvedSettings
import me.parham1995.notes.obsidian.SettingsFromVault
import me.parham1995.notes.obsidian.TomlValue
import me.parham1995.notes.obsidian.VaultSettingsDecline
import me.parham1995.notes.sync.VaultFilter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A vault's `.daftar.toml` as it is on the device: its text, and the folder it is the root of. */
data class VaultConfigFile(
    val text: String? = null,
    val root: File? = null,
)

/**
 * Where the `.daftar.toml` of a vault is read from. An interface for the
 * settings' sake: their tests have no vault on disk.
 */
fun interface VaultConfigSource {
    /** The file of [vaultId], or of the first vault when that is zero. */
    suspend fun of(vaultId: Long): VaultConfigFile

    /** The file of a vault was written, or may have been: what is kept of it is read again. */
    fun changed() = Unit

    companion object {
        /** No vault says anything. */
        val NONE = VaultConfigSource { VaultConfigFile() }
    }
}

/**
 * The `.daftar.toml` of each vault, from its working tree.
 *
 * Read from disk when asked, as the `.obsidian` files are: it is a small
 * file, and a second copy would have to be kept in step. One text is kept
 * per vault, for as long as the file's time and size say it is the same
 * file, because the settings are asked for far more often than they change.
 */
@Singleton
class VaultConfigStore
    @Inject
    constructor(
        private val files: VaultFileStore,
        private val vaults: VaultDao,
    ) : VaultConfigSource {
        private data class Kept(
            val stamp: String,
            val text: String,
        )

        private val kept = HashMap<Long, Kept>()

        override suspend fun of(vaultId: Long): VaultConfigFile {
            val id = vaultId.takeIf { it != 0L } ?: vaults.all().firstOrNull()?.id ?: return VaultConfigFile()
            return withContext(Dispatchers.IO) {
                val root = files.rootOf(id)
                val file = File(root, VaultFilter.DAFTAR)
                if (!file.isFile) return@withContext VaultConfigFile(root = root)
                val stamp = "${file.lastModified()}:${file.length()}"
                val text =
                    synchronized(kept) {
                        kept[id]?.takeIf { it.stamp == stamp }?.text
                            ?: runCatching { file.readText() }.getOrNull()?.also { kept[id] = Kept(stamp, it) }
                    }
                VaultConfigFile(text, root)
            }
        }

        override fun changed() {
            synchronized(kept) { kept.clear() }
        }

        /** What [vaultId] says of itself: `[reader]` and `[agents]`, which are always read. */
        suspend fun daftar(vaultId: Long): DaftarConfig = DaftarConfig.parse(of(vaultId).text.orEmpty())
    }

/**
 * Which of the app's settings came from the vault being read, and whether
 * this device follows the vault at all (`vault-config.md` in notes-linux's
 * `docs/specs/`, sections 1 and 4).
 */
data class FromVault(
    /** The switch: off, no app setting is read from any vault. `vault_settings = false` on the desktop. */
    val follow: Boolean = true,
    /** The keys this device declines one by one. `vault_settings = ["…"]` on the desktop. */
    val declined: Set<String> = emptySet(),
    /** The keys whose value in these settings is the vault's: shown with "From the vault", control off. */
    val applied: Set<String> = emptySet(),
    /** The keys the vault sets that this app has a setting for, followed or not. */
    val offered: Set<String> = emptySet(),
    /** The vault's `git.name` and `git.email`, which a commit uses when the app's own author has none. */
    val authorName: String = "",
    val authorEmail: String = "",
    /** Whether a change of a setting can go to the vault being read; see [locks]. */
    val writable: Boolean = false,
    /**
     * Keys the vault sets whose last change here went to the device, a
     * value a vault may not hold: the device's value holds until the
     * settings are read again, and then the vault's returns. Never stored.
     */
    val held: Set<String> = emptySet(),
    /**
     * Values a pinch or a run of taps has not settled on yet, by their key:
     * shown at once, and written once the hand has been still. Never stored.
     */
    val shown: Map<String, Any> = emptyMap(),
) {
    val decline: VaultSettingsDecline
        get() =
            when {
                !follow -> VaultSettingsDecline.All
                declined.isNotEmpty() -> VaultSettingsDecline.Keys(declined)
                else -> VaultSettingsDecline.None
            }

    operator fun contains(key: String): Boolean = key in applied

    /** This phone keeps [key] though the vault sets it: its row says "Kept on this device". */
    fun keeps(key: String): Boolean = follow && key in declined && key in offered

    /**
     * Whether the vault's file can be written from here. With it, a setting
     * the vault sets is changed in the vault; without, its control is off.
     * False until whoever shows the settings has asked.
     */
    fun locks(key: String): Boolean = key in applied && !writable
}

/**
 * One of the app's settings that a vault's `.daftar.toml` may hold: how the
 * vault's value is turned into the app's own, and how a value of the app's
 * is said in the vault.
 *
 * The keys are the desktop's, and some of this app's settings are in other
 * units. Such a one is said in the spec's key when its value can be said
 * there exactly, and in `[android]` when it cannot (`vault-config.md` in
 * notes-linux's `docs/specs/`, section 5, "On the phone").
 */
class SettingBinding<T : Any>(
    /** The key the declined list and "From the vault" go by. */
    val id: String,
    /** The vault's value as the app has it, or null when the vault says nothing the app can use. */
    val read: (ResolvedSettings, File?) -> T?,
    val get: (VaultSettings) -> T,
    val put: (VaultSettings, T) -> VaultSettings,
    /**
     * The key and value a change is written to the vault as, given the keys
     * the file has now; null for a value only the device can hold.
     */
    val said: (T, Map<String, TomlValue>) -> Pair<String, TomlValue>?,
    /** Writes the value to the device's own settings. */
    val keep: suspend (SettingsStore, T) -> Unit,
)

/**
 * Puts a vault's `.daftar.toml` over the app's own settings: the vault's
 * value over the app's, its `[android]` table over its shared sections, for
 * the keys of the spec's list that this app has a setting for and for the
 * settings only this app has. A key it has no setting for
 * (`appearance.font`, `terminal.font_size`) is ignored.
 *
 * - `appearance.text_size` is a size in points there and a scale here: the
 *   size over 14. A scale that is no whole size is
 *   `android.appearance.text_scale`, in percent.
 * - `appearance.text_width` is in em there and one of three widths here: 0
 *   is the whole window, up to [NARROW_EM] the narrow one, more the
 *   comfortable one. The two that are no exact em are
 *   `android.appearance.line_width`.
 * - `git.sync_interval` is the desktops' timer of commit, pull and push,
 *   and is neither read nor written here: a phone that wrote its hours
 *   there would turn automatic sync on for every computer. The phone's
 *   background refresh is `android.git.sync_interval`, in hours; 0 is off.
 * - `notifications.digest_time` is `HH:MM` there and an hour here.
 * - `capture.target` is where quick capture goes: a note's path is the
 *   scratchpad's path here; `"daily"` has no like and is as not there.
 * - `files.hidden` is the names left out of the lists; an empty list hides none.
 * - `home.image` is a picture in the vault, shown in place of the app's own;
 *   empty is none. The app's own picker takes a photo from outside the
 *   vault, so a picture chosen here is always the device's.
 */
object VaultOverlay {
    const val THEME = "appearance.theme"
    const val DARK_PALETTE = "appearance.dark_palette"
    const val LIVE_PREVIEW = "editor.live_preview"
    const val FORMAT_BAR = "editor.format_bar"
    const val TEXT_SIZE = "appearance.text_size"
    const val TEXT_WIDTH = "appearance.text_width"
    const val BLOCK_WIDTH = "appearance.block_width"
    const val REMOTE_IMAGES = SettingsFromVault.REMOTE_IMAGES
    const val FOLD_SECTIONS = "appearance.fold_sections"

    /** The phone's own: the shared `git.sync_interval` is the desktops' timer and is never read or written here. */
    const val SYNC_INTERVAL = "android.git.sync_interval"
    const val AUTHOR_NAME = "git.name"
    const val AUTHOR_EMAIL = "git.email"
    const val DIGEST = "notifications.digest"
    const val DIGEST_TIME = "notifications.digest_time"
    const val CAPTURE_TARGET = "capture.target"
    const val HIDDEN_FILES = "files.hidden"
    const val ANDROID_HIDDEN_FILES = "android.files.hidden"
    const val SHOW_HIDDEN = "files.show_hidden"
    const val HOME_IMAGE = "home.image"

    // The settings only this app has, in `[android]`.
    const val TEXT_SCALE = "android.appearance.text_scale"
    const val LINE_WIDTH = "android.appearance.line_width"
    const val LINE_SPACING = "android.appearance.line_spacing"
    const val PERSIAN_FONT = "android.appearance.persian_font"
    const val START_SCREEN = "android.appearance.start_screen"
    const val STYLUS_SPOTLIGHT = "android.appearance.stylus_spotlight"
    const val HIDE_COMPLETED = "android.appearance.hide_completed_tasks"
    const val HOLD_TO_DEFINE = "android.appearance.hold_to_define"
    const val KEEP_SCREEN_ON = "android.appearance.keep_screen_on"
    const val TAP_FULL_SCREEN = "android.appearance.tap_full_screen"
    const val WIDGET_OPACITY = "android.appearance.widget_opacity"
    const val BROWSER_SORT = "android.files.sort"
    const val WIFI_ONLY = "android.git.wifi_only"
    const val SSH_PORT_443 = "android.git.ssh_port_443"
    const val IMAGE_POLICY = "android.git.image_policy"
    const val WORK_START = "android.calendar.work_start"
    const val WORK_END = "android.calendar.work_end"

    /** The desktop's default text size, which this app's scale of 1 stands for. */
    const val DEFAULT_TEXT_SIZE = 14f

    /** The widest `text_width`, in em, that is this app's narrow column. */
    const val NARROW_EM = 38L

    private const val DAILY = "daily"
    private const val PERCENT = 100f
    private const val EXACT = 0.001f

    private fun flag(
        key: String,
        get: (VaultSettings) -> Boolean,
        put: (VaultSettings, Boolean) -> VaultSettings,
        keep: suspend (SettingsStore, Boolean) -> Unit,
    ) = SettingBinding(key, { it, _ -> it.flag(key) }, get, put, { v, _ -> key to TomlValue.Flag(v) }, keep)

    private fun <E : Enum<E>> choice(
        key: String,
        choices: List<E>,
        get: (VaultSettings) -> E,
        put: (VaultSettings, E) -> VaultSettings,
        keep: suspend (SettingsStore, E) -> Unit,
    ) = SettingBinding(
        key,
        { it, _ -> it.text(key)?.let { said -> choices.firstOrNull { c -> c.name.lowercase() == said } } },
        get,
        put,
        { v, _ -> key to TomlValue.Text(v.name.lowercase()) },
        keep,
    )

    private fun VaultSettings.reading(change: ReadingSettings.() -> ReadingSettings) = copy(reading = reading.change())

    val theme =
        choice(
            THEME,
            ThemeChoice.entries,
            { it.reading.theme },
            { s, v -> s.reading { copy(theme = v) } },
        ) { store, v ->
            store.setTheme(v)
        }

    /** The text's scale: the vault's size over 14, or its own scale in percent when the size is no whole one. */
    val textSize =
        SettingBinding(
            TEXT_SIZE,
            { it, _ -> it.whole(TEXT_SCALE)?.let { percent -> percent / PERCENT } ?: scale(it.whole(TEXT_SIZE)) },
            { it.reading.textScale },
            { s, v -> s.reading { copy(textScale = v) } },
            { scale, file ->
                val size = scale * DEFAULT_TEXT_SIZE
                val whole = Math.round(size)
                if (TEXT_SCALE !in file && kotlin.math.abs(size - whole) < EXACT) {
                    TEXT_SIZE to TomlValue.Whole(whole.toLong())
                } else {
                    TEXT_SCALE to TomlValue.Whole(Math.round(scale * PERCENT).toLong())
                }
            },
        ) { store, v -> store.setTextScale(v) }

    val lineWidth =
        SettingBinding(
            TEXT_WIDTH,
            { it, _ ->
                it.text(LINE_WIDTH)?.let { said -> LineWidth.entries.firstOrNull { w -> w.name.lowercase() == said } }
                    ?: width(it.whole(TEXT_WIDTH))
            },
            { it.reading.lineWidth },
            { s, v -> s.reading { copy(lineWidth = v) } },
            { width, file ->
                // The whole window is 0 em exactly; the other two are this app's own.
                if (width == LineWidth.FULL && LINE_WIDTH !in file) {
                    TEXT_WIDTH to TomlValue.Whole(0)
                } else {
                    LINE_WIDTH to TomlValue.Text(width.name.lowercase())
                }
            },
        ) { store, v -> store.setLineWidth(v) }

    /** How far past the line width a wide block may grow, in em; the same number here as in the spec. */
    val blockWidth =
        SettingBinding(
            BLOCK_WIDTH,
            { it, _ -> it.whole(BLOCK_WIDTH)?.toInt() },
            { it.reading.blockWidth },
            { s, v -> s.reading { copy(blockWidth = v) } },
            { v, _ -> BLOCK_WIDTH to TomlValue.Whole(v.toLong()) },
        ) { store, v -> store.setBlockWidth(v) }

    // Only ever `false` from a vault, and only `false` is written to one; the rule is the list's.
    val remoteImages =
        flag(REMOTE_IMAGES, { it.reading.remoteImages }, { s, v -> s.reading { copy(remoteImages = v) } }) { store, v ->
            store.setRemoteImages(v)
        }

    /** `"naz-soft"` is naz with its colour strength capped; `"naz"`, or nothing, is naz. */
    val softDark =
        SettingBinding(
            DARK_PALETTE,
            { it, _ -> it.text(DARK_PALETTE)?.let { said -> said == SOFT } },
            { it.reading.softDark },
            { s, v -> s.reading { copy(softDark = v) } },
            { soft, _ -> DARK_PALETTE to TomlValue.Text(if (soft) SOFT else "naz") },
        ) { store, v -> store.setSoftDark(v) }

    private const val SOFT = "naz-soft"

    /** The editor's drawn parts, the hidden marks first among them; `false` shows every mark. */
    val livePreview =
        flag(LIVE_PREVIEW, { it.reading.livePreview }, { s, v -> s.reading { copy(livePreview = v) } }) { store, v ->
            store.setLivePreview(v)
        }

    /** The editor's format bar, over the keyboard for a selection in one line. */
    val formatBar =
        flag(FORMAT_BAR, { it.reading.formatBar }, { s, v -> s.reading { copy(formatBar = v) } }) { store, v ->
            store.setFormatBar(v)
        }

    val foldSections =
        choice(
            FOLD_SECTIONS,
            FoldSections.entries,
            { it.reading.foldSections },
            { s, v -> s.reading { copy(foldSections = v) } },
        ) { store, v -> store.setFoldSections(v) }

    /** The background refresh, in hours; 0 is off. */
    val syncInterval =
        SettingBinding(
            SYNC_INTERVAL,
            { it, _ ->
                it.whole(SYNC_INTERVAL)?.toInt()
            },
            { if (it.backgroundSync) it.syncIntervalHours else 0 },
            { s, hours ->
                if (hours ==
                    0
                ) {
                    s.copy(backgroundSync = false)
                } else {
                    s.copy(backgroundSync = true, syncIntervalHours = hours)
                }
            },
            { hours, _ -> SYNC_INTERVAL to TomlValue.Whole(hours.toLong()) },
        ) { store, hours ->
            store.setBackgroundSync(hours > 0)
            if (hours > 0) store.setSyncIntervalHours(hours)
        }

    val digest =
        flag(DIGEST, { it.taskDigest }, { s, v -> s.copy(taskDigest = v) }) { store, v -> store.setTaskDigest(v) }

    val digestHour =
        SettingBinding(
            DIGEST_TIME,
            { it, _ -> it.text(DIGEST_TIME)?.substringBefore(':')?.toIntOrNull() },
            { it.taskDigestHour },
            { s, v -> s.copy(taskDigestHour = v) },
            { hour, _ -> DIGEST_TIME to TomlValue.Text("%02d:00".format(hour)) },
        ) { store, v -> store.setTaskDigestHour(v) }

    /** The scratchpad's path. Its vault is the device's to say. */
    val scratchpad =
        SettingBinding(
            CAPTURE_TARGET,
            { it, _ -> it.text(CAPTURE_TARGET)?.trim('/')?.takeIf { path -> path.isNotBlank() && path != DAILY } },
            { it.write.scratchpadPath },
            { s, v -> s.copy(write = s.write.copy(scratchpadPath = v)) },
            { path, _ ->
                path.trim().trim('/').takeIf { it.isNotEmpty() && it != DAILY }?.let {
                    CAPTURE_TARGET to
                        TomlValue.Text(it)
                }
            },
        ) { store, v ->
            store.setScratchpad(
                v,
                store.own
                    .first()
                    .write.scratchpadVaultId,
            )
        }

    /** The names the lists leave out; none hides nothing. The app's own switch is its list of agents' files, or none. */
    val hiddenFiles =
        SettingBinding<Set<String>>(
            HIDDEN_FILES,
            { it, _ -> it.texts(HIDDEN_FILES)?.toSet() },
            { if (it.reading.hideAgentFiles) it.reading.hiddenNames ?: HiddenFiles.AGENT_FILES else emptySet() },
            { s, names -> s.reading { copy(hideAgentFiles = names.isNotEmpty(), hiddenNames = names) } },
            { names, file ->
                // A list the person wrote, neither the app's own names nor empty, is not
                // replaced by a switch: the phone's choice goes to its own table.
                val shared = (file[HIDDEN_FILES] as? TomlValue.Texts)?.value?.toSet()
                val written = shared != null && shared.isNotEmpty() && shared != HiddenFiles.AGENT_FILES
                (if (written) ANDROID_HIDDEN_FILES else HIDDEN_FILES) to TomlValue.Texts(names.toList())
            },
        ) { store, names -> store.setHideAgentFiles(names.isNotEmpty()) }

    val showHidden =
        flag(
            SHOW_HIDDEN,
            { it.reading.showHiddenFiles },
            { s, v -> s.reading { copy(showHiddenFiles = v) } },
        ) { store, v ->
            store.setShowHiddenFiles(v)
        }

    /** The vault's picture, as a file on this device; empty is none. Read only: the app's picker is the device's. */
    val homeImage =
        SettingBinding(
            HOME_IMAGE,
            { it, root -> picture(it.text(HOME_IMAGE), root) },
            { it.reading.vaultHomePicture },
            // An empty path is the vault saying none: no picture of the app's own either.
            { s, v -> s.reading { copy(vaultHomePicture = v, homePicture = if (v.isEmpty()) "" else homePicture) } },
            { _, _ -> null },
        ) { _, _ -> }

    val lineSpacing =
        SettingBinding(
            LINE_SPACING,
            { it, _ -> it.whole(LINE_SPACING)?.let { percent -> percent / PERCENT } },
            { it.reading.lineSpacing },
            { s, v -> s.reading { copy(lineSpacing = v) } },
            { v, _ -> LINE_SPACING to TomlValue.Whole(Math.round(v * PERCENT).toLong()) },
        ) { store, v -> store.setLineSpacing(v) }

    val persianFont =
        flag(PERSIAN_FONT, { it.reading.persianFont }, { s, v -> s.reading { copy(persianFont = v) } }) { store, v ->
            store.setPersianFont(v)
        }

    val startScreen =
        choice(
            START_SCREEN,
            StartScreen.entries,
            { it.reading.startScreen },
            { s, v -> s.reading { copy(startScreen = v) } },
        ) {
            store,
            v,
            ->
            store.setStartScreen(v)
        }

    val stylusSpotlight =
        flag(STYLUS_SPOTLIGHT, { it.reading.stylusSpotlight }, { s, v -> s.reading { copy(stylusSpotlight = v) } }) {
            store,
            v,
            ->
            store.setStylusSpotlight(v)
        }

    val hideCompleted =
        flag(
            HIDE_COMPLETED,
            { it.reading.hideCompletedTasks },
            { s, v -> s.reading { copy(hideCompletedTasks = v) } },
        ) {
            store,
            v,
            ->
            store.setHideCompletedTasks(v)
        }

    val holdToDefine =
        flag(
            HOLD_TO_DEFINE,
            { it.reading.holdToDefine },
            { s, v -> s.reading { copy(holdToDefine = v) } },
        ) { store, v ->
            store.setHoldToDefine(v)
        }

    val keepScreenOn =
        flag(
            KEEP_SCREEN_ON,
            { it.reading.keepScreenOn },
            { s, v -> s.reading { copy(keepScreenOn = v) } },
        ) { store, v ->
            store.setKeepScreenOn(v)
        }

    val tapFullScreen =
        flag(TAP_FULL_SCREEN, { it.reading.tapForFullScreen }, { s, v -> s.reading { copy(tapForFullScreen = v) } }) {
            store,
            v,
            ->
            store.setTapForFullScreen(v)
        }

    val widgetOpacity =
        SettingBinding(
            WIDGET_OPACITY,
            { it, _ -> it.whole(WIDGET_OPACITY)?.toInt() },
            { it.reading.widgetOpacity },
            { s, v -> s.reading { copy(widgetOpacity = v) } },
            { v, _ -> WIDGET_OPACITY to TomlValue.Whole(v.toLong()) },
        ) { store, v -> store.setWidgetOpacity(v) }

    val browserSort =
        choice(
            BROWSER_SORT,
            BrowserSort.entries,
            { it.reading.browserSort },
            { s, v -> s.reading { copy(browserSort = v) } },
        ) {
            store,
            v,
            ->
            store.setBrowserSort(v)
        }

    val wifiOnly =
        flag(WIFI_ONLY, { it.syncOnWifiOnly }, { s, v -> s.copy(syncOnWifiOnly = v) }) { store, v ->
            store.setSyncOnWifiOnly(v)
        }

    val sshPort443 =
        flag(SSH_PORT_443, { it.sshOverPort443 }, { s, v -> s.copy(sshOverPort443 = v) }) { store, v ->
            store.setSshOverPort443(v)
        }

    val imagePolicy =
        choice(IMAGE_POLICY, ImagePolicy.entries, { it.imagePolicy }, { s, v -> s.copy(imagePolicy = v) }) { store, v ->
            store.setImagePolicy(v)
        }

    /** The working day's first hour. One that would not be before its end is as not there. */
    val workStart =
        SettingBinding(
            WORK_START,
            { it, _ -> it.whole(WORK_START)?.toInt() },
            { it.calendar.workStart },
            { s, v -> if (v < s.calendar.workEnd) s.copy(calendar = s.calendar.copy(workStart = v)) else s },
            { v, _ -> WORK_START to TomlValue.Whole(v.toLong()) },
        ) { store, v ->
            store.setCalendarWorkingHours(
                v,
                store.own
                    .first()
                    .calendar.workEnd,
            )
        }

    val workEnd =
        SettingBinding(
            WORK_END,
            { it, _ -> it.whole(WORK_END)?.toInt() },
            { it.calendar.workEnd },
            { s, v -> if (v > s.calendar.workStart) s.copy(calendar = s.calendar.copy(workEnd = v)) else s },
            { v, _ -> WORK_END to TomlValue.Whole(v.toLong()) },
        ) { store, v ->
            store.setCalendarWorkingHours(
                store.own
                    .first()
                    .calendar.workStart,
                v,
            )
        }

    /** Every setting of the app that a vault may hold. */
    val BINDINGS: List<SettingBinding<*>> =
        listOf(
            theme,
            textSize,
            lineWidth,
            blockWidth,
            remoteImages,
            softDark,
            livePreview,
            formatBar,
            foldSections,
            syncInterval,
            digest,
            digestHour,
            scratchpad,
            hiddenFiles,
            showHidden,
            homeImage,
            lineSpacing,
            persianFont,
            startScreen,
            stylusSpotlight,
            hideCompleted,
            holdToDefine,
            keepScreenOn,
            tapFullScreen,
            widgetOpacity,
            browserSort,
            wifiOnly,
            sshPort443,
            imagePolicy,
            workStart,
            workEnd,
        )

    /** Every key the declined list may name: the settings', and the author's two. */
    val KEYS: List<String> = BINDINGS.map { it.id } + listOf(AUTHOR_NAME, AUTHOR_EMAIL)

    /** [own] with what [file] says over it. [own]'s `fromVault` says whether the vault is followed. */
    fun apply(
        own: VaultSettings,
        file: VaultConfigFile,
    ): VaultSettings {
        val asked = own.fromVault
        val said = SettingsFromVault.resolved(file.text, root = file.root)
        val (settings, applied) = overlay(own, said, file.root, asked)
        // What the vault would set with nothing declined: what Settings can offer to follow again.
        val offered = overlay(VaultSettings(), said, file.root, FromVault()).second
        val follows = asked.follow
        return shown(settings, own.fromVault.shown).copy(
            fromVault =
                asked.copy(
                    applied = applied,
                    offered = offered,
                    authorName =
                        said
                            .text(AUTHOR_NAME)
                            .orEmpty()
                            .trim()
                            .takeIf {
                                follows &&
                                    AUTHOR_NAME !in asked.declined
                            }.orEmpty(),
                    authorEmail =
                        said
                            .text(AUTHOR_EMAIL)
                            .orEmpty()
                            .trim()
                            .takeIf {
                                follows &&
                                    AUTHOR_EMAIL !in asked.declined
                            }.orEmpty(),
                ),
        )
    }

    private fun overlay(
        own: VaultSettings,
        said: ResolvedSettings,
        root: File?,
        asked: FromVault,
    ): Pair<VaultSettings, Set<String>> {
        val applied = LinkedHashSet<String>()
        var out = own
        if (asked.follow) {
            BINDINGS.forEach { binding ->
                if (binding.id in asked.declined || binding.id in asked.held) return@forEach
                out = take(binding, out, said, root) ?: return@forEach
                applied += binding.id
            }
            // The author is not put over the app's own: the app's is first, each
            // of the two on its own, and a commit takes the vault's only when
            // the app has none (WriteSettings.authorWith).
            listOf(AUTHOR_NAME to own.write.authorName, AUTHOR_EMAIL to own.write.authorEmail).forEach { (key, mine) ->
                if (key !in asked.declined && said.text(key).orEmpty().isNotBlank() && mine.isBlank()) applied += key
            }
        }
        return out to applied
    }

    /** [settings] with the values a gesture has not settled yet, which the screen follows at once. */
    private fun shown(
        settings: VaultSettings,
        values: Map<String, Any>,
    ): VaultSettings =
        BINDINGS.fold(settings) { out, binding ->
            values[binding.id]?.let { show(binding, out, it) }
                ?: out
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> show(
        binding: SettingBinding<T>,
        settings: VaultSettings,
        value: Any,
    ): VaultSettings = binding.put(settings, value as T)

    private fun <T : Any> take(
        binding: SettingBinding<T>,
        settings: VaultSettings,
        said: ResolvedSettings,
        root: File?,
    ): VaultSettings? = binding.read(said, root)?.let { binding.put(settings, it) }

    /** The scale a text size of [points] is, or null when the app offers nothing that large or small. */
    private fun scale(points: Long?): Float? {
        val scale = (points ?: return null) / DEFAULT_TEXT_SIZE
        val offered = ReadingSettings.TEXT_SCALES
        return scale.takeIf { it >= offered.first() && it <= offered.last() }
    }

    private fun width(em: Long?): LineWidth? =
        when {
            em == null -> null
            em == 0L -> LineWidth.FULL
            em <= NARROW_EM -> LineWidth.NARROW
            else -> LineWidth.COMFORTABLE
        }

    /** The picture's file, when the vault names one and it is there; empty when it says none. */
    private fun picture(
        path: String?,
        root: File?,
    ): String? {
        if (path == null || root == null) return null
        if (path.isBlank()) return ""
        return File(root, path).takeIf { it.isFile }?.path
    }
}
