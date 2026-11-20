package me.parham1995.notes.sync

import java.io.IOException

/**
 * A read-only vault: a clone that pulls and never writes
 * (`read-only-vault.md` in notes-linux's `docs/specs/`, which Daftar for Linux
 * and the Mac follow too; change a rule here and theirs have to follow).
 *
 * Whether a clone is read-only is `daftar.readonly` in the clone's own git
 * config, never committed. These are the failures the spec names, by the
 * names it gives them, and the two rules that need no repository to state.
 */
sealed class ReadOnlyFailure(
    message: String,
) : IOException(message) {
    /** A write was asked of a read-only vault. Refused before the file is read. */
    class ReadOnly(
        val vault: String,
    ) : ReadOnlyFailure("$vault is read-only on this device: Daftar pulls and never writes ($KEY)")

    /**
     * A read-only clone could not fast-forward: it has commits or changes of
     * its own. Nothing is stashed, reset or rebased; [files] are what is in
     * the way, when that is known.
     */
    class ReadOnlyDiverged(
        val files: List<String>,
    ) : ReadOnlyFailure(
            "this read-only vault has changes of its own and the pull cannot fast-forward; " +
                "nothing was changed" + files.takeIf { it.isNotEmpty() }?.joinToString(", ", ": ").orEmpty(),
        )

    /** The remote refused a push because the key may only read. The commits already made stay. */
    class PushRefusedReadOnly(
        detail: String,
    ) : ReadOnlyFailure("this key cannot write to the repository - make the vault read-only in Settings ($detail)")

    companion object {
        const val KEY = "daftar.readonly"
    }
}

object ReadOnlyVault {
    private val FALSE = setOf("false", "no", "off", "0", "")

    /**
     * Whether a `daftar.readonly` of [value] means read-only. Null is the key
     * not set, which is not. `true`, `yes`, `on` and `1` are, `false`, `no`,
     * `off` and `0` are not, as git reads a boolean; and a value that is no
     * git boolean (`perhaps`) is read-only too: when in doubt the app does
     * not write.
     */
    fun isReadOnly(value: String?): Boolean = value != null && value.trim().lowercase() !in FALSE

    /**
     * Whether [message], what a refused push said, is the remote refusing a
     * key that may only read: GitHub's deploy key message, or its "write
     * access not granted". JGit's own words for a `receive-pack` the server
     * would not start are a third, since this app has no `git` whose stderr
     * to read. A key the server does not know at all is not one of them.
     */
    fun pushRefusedReadOnly(message: String): Boolean = REFUSALS.any { message.contains(it, ignoreCase = true) }

    private val REFUSALS =
        listOf(
            "marked as read only",
            "Write access to repository not granted",
            "push not permitted",
        )

    /**
     * A vault's address as it is written to `remote.<name>.url`: trimmed, or
     * null when nothing is left, which is refused and the old one stays.
     */
    fun address(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }
}
