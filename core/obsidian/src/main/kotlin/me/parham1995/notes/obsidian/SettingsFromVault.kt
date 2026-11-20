package me.parham1995.notes.obsidian

import java.io.File

/** Where a setting's value came from. */
enum class SettingSource {
    VAULT,
    COMPUTER,
    DEFAULT,
}

/**
 * Whether this device reads the app's settings from a vault: `vault_settings`
 * at the top of `config.toml` on the desktop, a switch in the app's settings
 * here. Not set is [None]: every key the vault may set is followed.
 */
sealed interface VaultSettingsDecline {
    data object None : VaultSettingsDecline

    /** `vault_settings = false`: no app setting is read from any vault. */
    data object All : VaultSettingsDecline

    /** `vault_settings = ["appearance.theme"]`: only these keys are declined. */
    data class Keys(
        val keys: Set<String>,
    ) : VaultSettingsDecline

    fun declines(key: String): Boolean =
        when (this) {
            None -> false
            All -> true
            is Keys -> key in keys
        }
}

/**
 * The settings after the vault's `.daftar.toml` and the device's own values
 * are put together. A key with no value in either is the default, which each
 * app holds itself, so it has no value here.
 */
data class ResolvedSettings(
    val values: Map<String, TomlValue> = emptyMap(),
    val sources: Map<String, SettingSource> = emptyMap(),
    /** Keys of the vault's file the allow-list refuses, by name, as `daftar-cli doctor` warns of them. */
    val refused: List<String> = emptyList(),
    /**
     * Keys the vault's file sets with a value that may be used, whether or
     * not this device follows it: what Settings offers to follow again.
     */
    val vaultSets: Set<String> = emptySet(),
) {
    operator fun get(key: String): TomlValue? = values[key]

    fun from(key: String): SettingSource = sources[key] ?: SettingSource.DEFAULT

    /** The vault's value of [key] when that is the one used, else null. */
    fun fromVault(key: String): TomlValue? = values[key].takeIf { from(key) == SettingSource.VAULT }

    fun whole(key: String): Long? = (values[key] as? TomlValue.Whole)?.value

    fun text(key: String): String? = (values[key] as? TomlValue.Text)?.value

    fun flag(key: String): Boolean? = (values[key] as? TomlValue.Flag)?.value

    fun texts(key: String): List<String>? = (values[key] as? TomlValue.Texts)?.value
}

/**
 * The app's settings from the vault: `vault-config.md` in notes-linux's
 * `docs/specs/`, which Daftar for Linux and the Mac follow too. Change a rule
 * here and theirs have to follow.
 *
 * For each key the first of: the vault's `.daftar.toml`, when the key is one
 * a vault may set and the device follows the vault; the device's own value;
 * the default. A repository is text other people can change, so it never
 * chooses a program to run, a file outside the vault or what leaves the
 * device: the keys are a list ([rule]), and a key of the app's own sections
 * that is not on it is refused and listed. A key the app does not know under
 * `notifications`, `scratchpad` or `capture`, a key of any other section and
 * an unknown top-level key are left alone.
 *
 * `[check]`, `[audit]`, `[reader]` and `[agents]` are what the vault says of
 * itself, not app settings: they are always read ([DaftarConfig]) and never
 * refused here.
 */
object SettingsFromVault {
    const val VAULT_SETTINGS = "vault_settings"
    const val REMOTE_IMAGES = "appearance.remote_images"

    /**
     * The sections of the app's own settings. A key in one of them that is
     * not on the list is refused; every other section (`[check]`, `[audit]`,
     * `[reader]`, `[agents]`, `[okf]`) is not an app setting and is left alone.
     */
    private val APP_SECTIONS =
        setOf(
            "appearance",
            "calendar",
            "editor",
            "git",
            "notifications",
            "terminal",
            "scratchpad",
            "capture",
            "meaning",
            "home",
            "files",
            "wakatime",
            "export",
        )

    /** Sections a vault may set whole: a key there the app does not know is left alone, not refused. */
    private val OPEN_SECTIONS = setOf("notifications", "scratchpad", "capture")

    /** The keys at the top of the desktop's `config.toml`: never the vault's to say. Any other top-level key is left alone. */
    private val TOP = setOf("vault", VAULT_SETTINGS)

    private val THEMES = setOf("system", "light", "dark")
    private val FOLDS = setOf("vault", "always", "never")
    private val TIME = Regex("""([01]\d|2[0-3]):[0-5]\d""")

    // The ranges are those of the apps' own controls, the same in the three apps.
    private const val MAX_TEXT_SIZE = 28L
    private const val MIN_TEXT_SIZE = 10L
    private const val MAX_TEXT_WIDTH = 400L
    private const val PERCENT = 100L
    private const val MAX_FONT_SIZE = 24L
    private const val MIN_FONT_SIZE = 9L
    private const val MINUTES_IN_WEEK = 10_080L
    private const val MINUTES_IN_DAY = 1_440L

    /** What a vault may say for one key. */
    private sealed interface Rule {
        /** Never the vault's to say. */
        data object Never : Rule

        /** Allowed, when [valid] of the value; [root] is the vault's folder when it is known. */
        class Allowed(
            val valid: (value: TomlValue, root: File?) -> Boolean,
        ) : Rule
    }

    private fun allowed(valid: (TomlValue) -> Boolean) = Rule.Allowed { value, _ -> valid(value) }

    private val flag = allowed { it is TomlValue.Flag }

    /** Any text of one line. */
    private val text = allowed { it is TomlValue.Text && oneLine(it.value) }

    /** Text of one line that says something: a font, a name. */
    private val named = allowed { it is TomlValue.Text && oneLine(it.value) && it.value.isNotBlank() }

    private fun oneLine(text: String): Boolean = text.none { it.isISOControl() }

    private val path = Rule.Allowed { value, root -> value is TomlValue.Text && inVault(value.value, root) }

    private fun whole(range: LongRange) = allowed { it is TomlValue.Whole && it.value in range }

    private fun oneOf(choices: Set<String>) = allowed { it is TomlValue.Text && it.value in choices }

    private val RULES: Map<String, Rule> =
        mapOf(
            "appearance.theme" to oneOf(THEMES),
            "appearance.text_size" to whole(MIN_TEXT_SIZE..MAX_TEXT_SIZE),
            "appearance.font" to named,
            "appearance.code_font" to named,
            "appearance.text_width" to whole(0..MAX_TEXT_WIDTH),
            "appearance.reading_line" to whole(0..PERCENT),
            "appearance.fold_sections" to oneOf(FOLDS),
            "appearance.persian_calendar" to flag,
            // Only `false`: a vault can stop pictures from the web, never turn them on.
            REMOTE_IMAGES to allowed { it == TomlValue.Flag(false) },
            "calendar.place" to text,
            "editor.live_preview" to flag,
            "editor.format_on_save" to flag,
            "git.sync_interval" to whole(0..MINUTES_IN_WEEK),
            "git.pull_on_launch" to flag,
            "git.name" to named,
            "git.email" to named,
            "notifications.meetings" to flag,
            "notifications.meeting_lead" to whole(0..MINUTES_IN_DAY),
            "notifications.digest" to flag,
            "notifications.digest_time" to allowed { it is TomlValue.Text && TIME.matches(it.value) },
            "terminal.font_size" to whole(MIN_FONT_SIZE..MAX_FONT_SIZE),
            "home.image" to path,
            "export.logo" to path,
            "export.footer" to text,
            "export.contents" to flag,
            "export.landscape" to flag,
            "scratchpad.note" to path,
            // "daily" is today's daily note; anything else is a note's path.
            "capture.target" to
                Rule.Allowed { value, root ->
                    value is TomlValue.Text && (value.value == "daily" || inVault(value.value, root))
                },
            "capture.heading" to text,
            "files.hidden" to allowed { it is TomlValue.Texts },
            "files.show_hidden" to flag,
            "capture.hotkey" to text,
        )

    /**
     * The rule of [key], or null for a key that is neither on the list nor
     * refused: one the app does not know under an open section, one of a
     * section that is not the app's, or an unknown top-level key. Such a key
     * is left alone.
     */
    private fun rule(key: String): Rule? {
        RULES[key]?.let { return it }
        val section = key.substringBeforeLast('.', "")
        val refused = if (section.isEmpty()) key in TOP else section in APP_SECTIONS && section !in OPEN_SECTIONS
        return if (refused) Rule.Never else null
    }

    /** Whether [key] is one a vault may set at all. */
    fun mayBeSet(key: String): Boolean = rule(key) is Rule.Allowed

    /**
     * Whether [path] is a file's path in the vault: relative, with no `..`,
     * and, when the vault's folder [root] is known, not a link that leaves
     * the vault once resolved. Empty is in it: it is how "no file" is said.
     */
    fun inVault(
        path: String,
        root: File?,
    ): Boolean {
        if (path.isEmpty()) return true
        if (path.startsWith("/") || path.startsWith("~") || path.startsWith("\\")) return false
        if (path.length > 1 && path[1] == ':') return false
        if (path.split('/', '\\').any { it == ".." }) return false
        if (root == null) return true
        return runCatching {
            val inside = root.canonicalFile
            val target = File(root, path).canonicalFile
            target == inside || target.path.startsWith(inside.path + File.separator)
        }.getOrDefault(false)
    }

    /**
     * Puts the [vault]'s `.daftar.toml` (null when it has none) over the
     * device's own values, [computer], by the keys' full names.
     */
    fun resolved(
        vault: String?,
        computer: Map<String, TomlValue> = emptyMap(),
        decline: VaultSettingsDecline = VaultSettingsDecline.None,
        root: File? = null,
    ): ResolvedSettings {
        val values = LinkedHashMap<String, TomlValue>()
        val sources = LinkedHashMap<String, SettingSource>()
        computer.forEach { (key, value) ->
            if (key == VAULT_SETTINGS) return@forEach
            values[key] = value
            sources[key] = SettingSource.COMPUTER
        }
        val refused = mutableListOf<String>()
        val vaultSets = LinkedHashSet<String>()
        DaftarToml.parse(vault.orEmpty()).forEach { (key, value) ->
            val rule = rule(key) ?: return@forEach
            if (rule !is Rule.Allowed) {
                refused += key
                return@forEach
            }
            // A value of the wrong type, or out of the key's range, is as not there.
            if (!rule.valid(value, root)) return@forEach
            vaultSets += key
            if (decline.declines(key)) return@forEach
            values[key] = value
            sources[key] = SettingSource.VAULT
        }
        return ResolvedSettings(values, sources, refused, vaultSets)
    }

    /**
     * [resolved] over two texts, the device's being a `config.toml` with
     * `vault_settings` at its top: the desktop apps' form of the rule, and
     * the one the spec's tests are written in.
     */
    fun resolved(
        vault: String?,
        computer: String,
        root: File? = null,
    ): ResolvedSettings {
        val own = DaftarToml.parse(computer)
        val decline =
            when (val said = own[VAULT_SETTINGS]) {
                TomlValue.Flag(false) -> VaultSettingsDecline.All
                is TomlValue.Texts -> VaultSettingsDecline.Keys(said.value.toSet())
                else -> VaultSettingsDecline.None
            }
        return resolved(vault, own, decline, root)
    }
}
