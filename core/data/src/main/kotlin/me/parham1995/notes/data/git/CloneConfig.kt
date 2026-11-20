package me.parham1995.notes.data.git

import me.parham1995.notes.sync.ReadOnlyVault
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import java.io.File

/**
 * What one clone is, in its own git config (`.git/config`): whether it is
 * read-only (`daftar.readonly`) and its address (`remote.<name>.url`). Never
 * committed, and read the same by anything else that opens the clone.
 * `read-only-vault.md` in notes-linux's `docs/specs/` has the rules, sections
 * 1 and 4.
 *
 * The clone's key and its author are not here: on Android the clone is the
 * app's own, and both are in the app's settings already. `core.sshCommand`
 * (the spec's section 2) is for a `git` that runs the system's `ssh`; this
 * app connects with Apache sshd and its own key, so it neither reads nor
 * writes it.
 *
 * [AndroidGitEnvironment.install] has to have run before any of this.
 */
object CloneConfig {
    private const val SECTION = "daftar"
    private const val READ_ONLY = "readonly"

    /** Whether [workTree] is a clone at all. A vault that is not has none of these settings. */
    fun isRepository(workTree: File): Boolean = File(workTree, Constants.DOT_GIT).isDirectory

    /**
     * Whether the clone at [workTree] is read-only: `daftar.readonly` in the
     * clone's own config and no other. The user's and the system's configs
     * are not read, so a device cannot make every vault read-only by
     * accident. A folder that is no repository is never read-only.
     */
    fun isReadOnly(workTree: File): Boolean {
        if (!isRepository(workTree)) return false
        // On its own, with no parent: `repository.config` answers from the
        // user's config too when the clone's has no value.
        val local = FileBasedConfig(File(File(workTree, Constants.DOT_GIT), Constants.CONFIG), FS.DETECTED)
        val value =
            try {
                local.load()
                local.getString(SECTION, null, READ_ONLY)
            } catch (_: Exception) {
                // A config that cannot be read is doubt, and in doubt the app does not write.
                return true
            }
        // `readonly` with no `=` is true to git.
        if (value != null && Config.isMissing(value)) return true
        return ReadOnlyVault.isReadOnly(value)
    }

    /** Turns read-only on or off, which changes that one key and nothing else of the clone. */
    fun setReadOnly(
        workTree: File,
        readOnly: Boolean,
    ) {
        if (!isRepository(workTree)) return
        Git.open(workTree).use { git ->
            val config = git.repository.config
            if (readOnly) {
                config.setBoolean(SECTION, null, READ_ONLY, true)
            } else {
                config.unset(SECTION, null, READ_ONLY)
            }
            config.save()
        }
    }

    /** The remote the clone pulls from: the one of the branch's upstream, else `origin`. */
    fun remoteName(workTree: File): String {
        if (!isRepository(workTree)) return Constants.DEFAULT_REMOTE_NAME
        return Git.open(workTree).use { git ->
            val repository = git.repository
            repository.branch
                ?.let {
                    repository.config.getString(
                        ConfigConstants.CONFIG_BRANCH_SECTION,
                        it,
                        ConfigConstants.CONFIG_KEY_REMOTE,
                    )
                }?.takeIf { it.isNotBlank() && it != "." }
                ?: Constants.DEFAULT_REMOTE_NAME
        }
    }

    /** The vault's address: where this clone pulls from and pushes to. Null when it has no remote. */
    fun address(workTree: File): String? {
        if (!isRepository(workTree)) return null
        val name = remoteName(workTree)
        return Git.open(workTree).use { git ->
            git.repository.config
                .getString(ConfigConstants.CONFIG_REMOTE_SECTION, name, ConfigConstants.CONFIG_KEY_URL)
                ?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Writes the address, trimmed, to the upstream's remote, and says whether
     * it did: an empty one is refused and the old one stays. With no remote
     * yet, `origin` is added. Nothing is fetched; the next sync is what finds
     * out whether the new address answers.
     */
    fun setAddress(
        workTree: File,
        raw: String,
    ): Boolean {
        val address = ReadOnlyVault.address(raw) ?: return false
        if (!isRepository(workTree)) return false
        val name = remoteName(workTree)
        Git.open(workTree).use { git ->
            val config = git.repository.config
            val section = ConfigConstants.CONFIG_REMOTE_SECTION
            config.setString(section, name, ConfigConstants.CONFIG_KEY_URL, address)
            if (config.getStringList(section, name, FETCH).isEmpty()) {
                config.setString(section, name, FETCH, "+refs/heads/*:refs/remotes/$name/*")
            }
            config.save()
        }
        return true
    }

    private const val FETCH = "fetch"
}
