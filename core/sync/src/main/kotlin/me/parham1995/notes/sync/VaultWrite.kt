package me.parham1995.notes.sync

/**
 * Who a commit is attributed to.
 *
 * Configurable rather than fixed, because these commits land in the same
 * history as the ones made at a desk: an author of "Daftar" on every phone
 * edit makes `git log --author` useless for the person who wrote both.
 */
data class Author(
    val name: String,
    val email: String,
) {
    val isUsable: Boolean get() = name.isNotBlank() && email.contains('@')
}

/**
 * A change expressed as a function of the file's current text.
 *
 * This is the whole reason editing from a phone is safe here. A patch computed
 * against yesterday's content either applies to something it was not written
 * for or has to be merged; an edit that is re-run against whatever the file now
 * says has neither problem. Every conflict is answered by reading the file
 * again and calling this again, never by forcing the earlier result through.
 *
 * Returning null means the edit no longer applies -- the task is already ticked,
 * the line it named is gone -- which is a quiet success, not a failure.
 */
fun interface TextEdit {
    fun applyTo(current: String?): String?
}

/** What came of asking a transport to write. */
sealed interface WriteOutcome {
    data class Written(
        val commit: String,
        val text: String,
    ) : WriteOutcome

    /** The file already said what the edit wanted it to say. */
    data object NotApplicable : WriteOutcome
}

/**
 * The write half of a transport, kept apart from [VaultSync] so that reading
 * never depends on a credential that can write.
 */
interface VaultWriter {
    /**
     * Whether this credential may push, asked of the host rather than assumed.
     *
     * A read-only deploy key and a `Contents: read-only` token both look
     * exactly like a working credential until the moment of the push, so this
     * is what every write affordance in the app is gated on.
     */
    suspend fun canPush(): Boolean

    /** Applies [edit] to [path] and commits the result. */
    suspend fun write(
        path: String,
        message: String,
        author: Author,
        edit: TextEdit,
    ): WriteOutcome
}

/**
 * Writes through the GitHub contents API: read the file with its blob sha,
 * apply the edit, put it back quoting that sha.
 *
 * GitHub refuses the put if the sha no longer matches, so a phone that has been
 * offline for a day cannot overwrite a morning's work at the desk. That refusal
 * is answered by reading again and re-applying, which is why [TextEdit] is a
 * function of the current text rather than a diff.
 */
class RestVaultWriter(
    private val client: GitHubClient,
    private val branch: String,
    private val attempts: Int = DEFAULT_ATTEMPTS,
    private val log: suspend (String) -> Unit = {},
) : VaultWriter {
    override suspend fun canPush(): Boolean = client.repository().canPush

    override suspend fun write(
        path: String,
        message: String,
        author: Author,
        edit: TextEdit,
    ): WriteOutcome {
        repeat(attempts) {
            val existing = client.file(path, branch)
            val updated = edit.applyTo(existing?.text) ?: return WriteOutcome.NotApplicable
            if (updated == existing?.text) return WriteOutcome.NotApplicable
            try {
                val commit = client.putFile(path, updated, existing?.sha, branch, message, author)
                return WriteOutcome.Written(commit, updated)
            } catch (_: GitHubException.Conflict) {
                log("$path moved under the edit - reading it again and re-applying")
            }
        }
        throw GitHubException.Conflict(path)
    }

    private companion object {
        const val DEFAULT_ATTEMPTS = 3
    }
}

/**
 * The sha git would give this content.
 *
 * Computed rather than asked for, so a file the app just wrote can be recorded
 * in the manifest with the same identity the next sync will see. Without it the
 * manifest holds the old sha and the very next refresh pulls the file back down
 * over the edit that has already landed.
 */
fun gitBlobSha(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    digest.update("blob ${bytes.size}".toByteArray())
    digest.update(0)
    digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
}
