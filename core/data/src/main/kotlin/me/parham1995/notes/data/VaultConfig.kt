package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.obsidian.DaftarConfig
import me.parham1995.notes.obsidian.SettingsFromVault
import me.parham1995.notes.obsidian.VaultSettingsDecline
import me.parham1995.notes.sync.VaultFilter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

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
) {
    val decline: VaultSettingsDecline
        get() =
            when {
                !follow -> VaultSettingsDecline.All
                declined.isNotEmpty() -> VaultSettingsDecline.Keys(declined)
                else -> VaultSettingsDecline.None
            }

    operator fun contains(key: String): Boolean = key in applied
}

/**
 * Puts a vault's `.daftar.toml` over the app's own settings: the vault's
 * value over the app's, for the keys of the spec's list that this app has a
 * setting for. A key it has no setting for (`appearance.font`,
 * `terminal.font_size`) is ignored.
 *
 * The keys are the desktop's, and some of this app's settings are in other
 * units, so each is turned into the app's own:
 *
 * - `appearance.text_size` is a size in points there and a scale here: the
 *   size over 14, the desktop's default. A size whose scale is outside what
 *   the app offers is as not there.
 * - `appearance.text_width` is in em there and one of three widths here: 0
 *   is the whole window, up to [NARROW_EM] the narrow one, more the
 *   comfortable one.
 * - `git.sync_interval` is minutes between syncs there and a background
 *   refresh in hours here: 0 turns it off, anything else is rounded up to
 *   whole hours, a day at most.
 * - `notifications.digest_time` is `HH:MM` there and an hour here.
 * - `capture.target` is where quick capture goes: a note's path is the
 *   scratchpad's path here; `"daily"` has no like and is as not there.
 * - `files.hidden` is the names left out of the lists, in place of the
 *   app's own list of agents' files; an empty list hides none.
 * - `home.image` is a picture in the vault, shown in place of the app's own;
 *   empty is none.
 */
object VaultOverlay {
    const val THEME = "appearance.theme"
    const val TEXT_SIZE = "appearance.text_size"
    const val TEXT_WIDTH = "appearance.text_width"
    const val REMOTE_IMAGES = SettingsFromVault.REMOTE_IMAGES
    const val FOLD_SECTIONS = "appearance.fold_sections"
    const val SYNC_INTERVAL = "git.sync_interval"
    const val AUTHOR_NAME = "git.name"
    const val AUTHOR_EMAIL = "git.email"
    const val DIGEST = "notifications.digest"
    const val DIGEST_TIME = "notifications.digest_time"
    const val CAPTURE_TARGET = "capture.target"
    const val HIDDEN_FILES = "files.hidden"
    const val SHOW_HIDDEN = "files.show_hidden"
    const val HOME_IMAGE = "home.image"

    /** Every key of the spec's list that this app has a setting for. */
    val KEYS =
        listOf(
            THEME,
            TEXT_SIZE,
            TEXT_WIDTH,
            REMOTE_IMAGES,
            FOLD_SECTIONS,
            SYNC_INTERVAL,
            AUTHOR_NAME,
            AUTHOR_EMAIL,
            DIGEST,
            DIGEST_TIME,
            CAPTURE_TARGET,
            HIDDEN_FILES,
            SHOW_HIDDEN,
            HOME_IMAGE,
        )

    /** The desktop's default text size, which this app's scale of 1 stands for. */
    const val DEFAULT_TEXT_SIZE = 14f

    /** The widest `text_width`, in em, that is this app's narrow column. */
    const val NARROW_EM = 38L

    private const val MINUTES_PER_HOUR = 60.0
    private const val HOURS_PER_DAY = 24
    private const val DAILY = "daily"

    /** [own] with what [file] says over it. [own]'s `fromVault` says whether the vault is followed. */
    fun apply(
        own: VaultSettings,
        file: VaultConfigFile,
    ): VaultSettings {
        val asked = own.fromVault
        val (settings, applied) = overlay(own, file, asked.decline)
        // What the vault would set with nothing declined: what Settings can offer to follow again.
        val offered = overlay(VaultSettings(), file, VaultSettingsDecline.None).second
        val author = SettingsFromVault.resolved(file.text, decline = asked.decline, root = file.root)
        return settings.copy(
            fromVault =
                asked.copy(
                    applied = applied,
                    offered = offered,
                    authorName = author.text(AUTHOR_NAME).orEmpty().trim(),
                    authorEmail = author.text(AUTHOR_EMAIL).orEmpty().trim(),
                ),
        )
    }

    private fun overlay(
        own: VaultSettings,
        file: VaultConfigFile,
        decline: VaultSettingsDecline,
    ): Pair<VaultSettings, Set<String>> {
        val resolved = SettingsFromVault.resolved(file.text, decline = decline, root = file.root)
        val applied = LinkedHashSet<String>()
        var out = own

        fun <T : Any> take(
            key: String,
            value: T?,
            put: (VaultSettings, T) -> VaultSettings,
        ) {
            if (value == null) return
            out = put(out, value)
            applied += key
        }

        take(THEME, theme(resolved.text(THEME))) { s, v -> s.copy(reading = s.reading.copy(theme = v)) }
        take(TEXT_SIZE, scale(resolved.whole(TEXT_SIZE))) { s, v -> s.copy(reading = s.reading.copy(textScale = v)) }
        take(TEXT_WIDTH, width(resolved.whole(TEXT_WIDTH))) { s, v -> s.copy(reading = s.reading.copy(lineWidth = v)) }
        // Only ever `false` from a vault; the rule is the list's.
        take(REMOTE_IMAGES, resolved.flag(REMOTE_IMAGES)) { s, v -> s.copy(reading = s.reading.copy(remoteImages = v)) }
        take(FOLD_SECTIONS, folds(resolved.text(FOLD_SECTIONS))) { s, v ->
            s.copy(reading = s.reading.copy(foldSections = v))
        }
        take(SYNC_INTERVAL, resolved.whole(SYNC_INTERVAL)) { s, minutes ->
            if (minutes == 0L) {
                s.copy(backgroundSync = false)
            } else {
                s.copy(
                    backgroundSync = true,
                    syncIntervalHours = ceil(minutes / MINUTES_PER_HOUR).toInt().coerceIn(1, HOURS_PER_DAY),
                )
            }
        }
        take(DIGEST, resolved.flag(DIGEST)) { s, v -> s.copy(taskDigest = v) }
        take(DIGEST_TIME, resolved.text(DIGEST_TIME)?.substringBefore(':')?.toIntOrNull()) { s, v ->
            s.copy(taskDigestHour = v)
        }
        take(
            CAPTURE_TARGET,
            resolved.text(CAPTURE_TARGET)?.trim('/')?.takeIf { it.isNotBlank() && it != DAILY },
        ) { s, v ->
            s.copy(write = s.write.copy(scratchpadPath = v))
        }
        take(HIDDEN_FILES, resolved.texts(HIDDEN_FILES)) { s, names ->
            s.copy(reading = s.reading.copy(hideAgentFiles = names.isNotEmpty(), hiddenNames = names.toSet()))
        }
        take(SHOW_HIDDEN, resolved.flag(SHOW_HIDDEN)) { s, v -> s.copy(reading = s.reading.copy(showHiddenFiles = v)) }
        // An empty path is the vault saying none: no picture of the app's own either.
        take(HOME_IMAGE, picture(resolved.text(HOME_IMAGE), file.root)) { s, v ->
            s.copy(
                reading =
                    s.reading.copy(
                        vaultHomePicture = v,
                        homePicture = if (v.isEmpty()) "" else s.reading.homePicture,
                    ),
            )
        }
        // The author is not put over the app's own: the app's is first, each
        // of the two on its own, and a commit takes the vault's only when
        // the app has none (WriteSettings.authorWith). So the vault's is
        // "applied" only where the app's own is empty.
        if (resolved.text(AUTHOR_NAME).orEmpty().isNotBlank() && own.write.authorName.isBlank()) applied += AUTHOR_NAME
        if (resolved.text(AUTHOR_EMAIL).orEmpty().isNotBlank() &&
            own.write.authorEmail.isBlank()
        ) {
            applied += AUTHOR_EMAIL
        }
        return out to applied
    }

    private fun theme(said: String?): ThemeChoice? =
        when (said) {
            "system" -> ThemeChoice.SYSTEM
            "light" -> ThemeChoice.LIGHT
            "dark" -> ThemeChoice.DARK
            else -> null
        }

    private fun folds(said: String?): FoldSections? =
        when (said) {
            "vault" -> FoldSections.VAULT
            "always" -> FoldSections.ALWAYS
            "never" -> FoldSections.NEVER
            else -> null
        }

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

    /** The picture's file, when the vault names one and it is there. Empty names none, which is the app's own. */
    private fun picture(
        path: String?,
        root: File?,
    ): String? {
        if (path == null || root == null) return null
        if (path.isBlank()) return ""
        return File(root, path).takeIf { it.isFile }?.path
    }
}
