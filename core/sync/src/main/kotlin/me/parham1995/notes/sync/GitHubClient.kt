package me.parham1995.notes.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/** Which repository to read, and with what credential. */
data class GitHubConfig(
    val owner: String,
    val repo: String,
    val branch: String? = null,
    val token: String,
    val apiBase: String = "https://api.github.com",
)

/** A response that may have been answered from the caller's own cache. */
sealed interface Conditional<out T> {
    data class Fresh<T>(
        val value: T,
        val etag: String?,
    ) : Conditional<T>

    /**
     * The resource is unchanged. GitHub does not bill a `304` against the rate
     * limit, which is what makes a quiet refresh essentially free.
     */
    data object NotModified : Conditional<Nothing>
}

/** Everything the API can say that the caller has to act on differently. */
sealed class GitHubException(
    message: String,
) : IOException(message) {
    /** The token is missing, malformed, revoked or expired. */
    class Unauthorized : GitHubException("the access token was rejected")

    /**
     * The token is valid but cannot see this repository -- wrong resource
     * owner, repository not selected, or it was renamed or deleted. GitHub
     * deliberately returns 404 rather than 403 so a token cannot be used to
     * probe for private repositories.
     */
    class NotFound(
        resource: String,
    ) : GitHubException("not found, or not visible to this token: $resource")

    /** Primary rate limit. [resetEpochSeconds] is when it lifts. */
    class RateLimited(
        val resetEpochSeconds: Long,
    ) : GitHubException("rate limit exhausted until $resetEpochSeconds")

    /** Secondary rate limit: back off, do not retry immediately. */
    class SlowDown(
        val retryAfterSeconds: Long,
    ) : GitHubException("secondary rate limit, retry after ${retryAfterSeconds}s")

    class Unexpected(
        val code: Int,
        body: String,
    ) : GitHubException("unexpected response $code: $body")

    /**
     * The credential is valid and can see the repository, but not do this.
     *
     * Distinct from [RateLimited] and [SlowDown], which are also `403`: a
     * read-only token answers a write with this, and backing off and retrying
     * a permission failure forever is the wrong response to it.
     */
    class Forbidden(
        detail: String,
    ) : GitHubException("refused: $detail")

    /**
     * The file moved on between reading it and writing it back. The edit is
     * re-applied to the new content rather than retried as-is.
     */
    class Conflict(
        path: String,
    ) : GitHubException("$path changed upstream while it was being edited")

    /** The tree was truncated, so it cannot be treated as a full listing. */
    class TreeTruncated : GitHubException("the repository tree was truncated and cannot be used as a manifest")
}

/**
 * A thin client over the five REST endpoints a read-only sync needs. Retrofit
 * would be more machinery than five calls justify.
 */
class GitHubClient(
    private val config: GitHubConfig,
    private val http: OkHttpClient = OkHttpClient(),
    private val limiter: RateLimiter = RateLimiter(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String) = "${config.apiBase}/repos/${config.owner}/${config.repo}$path"

    private fun request(
        url: String,
        accept: String = "application/vnd.github+json",
        etag: String? = null,
    ): Request =
        Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()

    private suspend fun <T> call(
        request: Request,
        resource: String,
        onSuccess: (Response) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            limiter.acquire()
            http.newCall(request).execute().use { response ->
                limiter.observe(response)
                when {
                    // 304 is not "successful" to OkHttp, but it is exactly what a
                    // conditional request wants back.
                    response.isSuccessful || response.code == 304 -> onSuccess(response)
                    response.code == 401 -> throw GitHubException.Unauthorized()
                    response.code == 404 -> throw GitHubException.NotFound(resource)
                    response.code == 409 -> throw GitHubException.Conflict(resource)
                    // GitHub answers a stale blob sha with 422 as often as
                    // with 409, and the two mean the same thing to a write.
                    response.code == 422 && STALE_SHA in response.peekBody(BODY_PEEK).string() ->
                        throw GitHubException.Conflict(resource)
                    response.code == 403 || response.code == 429 -> throw response.toLimitException()
                    else -> throw GitHubException.Unexpected(response.code, response.peekBody(BODY_PEEK).string())
                }
            }
        }

    private fun Response.toLimitException(): GitHubException {
        header("retry-after")?.toLongOrNull()?.let { return GitHubException.SlowDown(it) }
        if (header("x-ratelimit-remaining") == "0") {
            val reset = header("x-ratelimit-reset")?.toLongOrNull() ?: 0L
            return GitHubException.RateLimited(reset)
        }
        // A 403 carrying none of the rate-limit headers is a permission
        // answer, not a pacing one -- which is what a read-only token returns
        // to a write. Treating it as a secondary limit would have the app back
        // off and try again forever over something no amount of waiting fixes.
        if (code == 403) return GitHubException.Forbidden(peekBody(BODY_PEEK).string())
        return GitHubException.SlowDown(DEFAULT_BACKOFF_SECONDS)
    }

    /** Confirms the token works and the repository is visible. */
    suspend fun repository(): RepositoryInfo =
        call(request(url("")), "${config.owner}/${config.repo}") { response ->
            val dto = json.decodeFromString<RepoDto>(response.body.string())
            RepositoryInfo(
                dto.fullName,
                dto.defaultBranch,
                dto.private,
                dto.pushedAt,
                canPush = dto.permissions?.push == true,
            )
        }

    /**
     * The commit the branch points at. This is the one call a quiet refresh
     * makes: with a matching ETag it answers `304` and costs no quota.
     */
    suspend fun head(
        branch: String,
        etag: String? = null,
    ): Conditional<String> {
        val request = request(url("/git/ref/heads/$branch"), etag = etag)
        return call(request, "heads/$branch") { response ->
            if (response.code == 304) {
                Conditional.NotModified
            } else {
                val dto = json.decodeFromString<RefDto>(response.body.string())
                Conditional.Fresh(dto.`object`.sha, response.header("etag"))
            }
        }
    }

    /** The full recursive tree at [commit], filtered down to vault content. */
    suspend fun tree(
        commit: String,
        filter: VaultFilter,
    ): List<VaultEntry> =
        call(request(url("/git/trees/$commit?recursive=1")), "tree/$commit") { response ->
            val dto = json.decodeFromString<TreeDto>(response.body.string())
            if (dto.truncated) throw GitHubException.TreeTruncated()
            dto.tree.mapNotNull { entry ->
                if (entry.type != "blob") return@mapNotNull null
                val sha = entry.sha ?: return@mapNotNull null
                val kind = filter.kindOf(entry.path) ?: return@mapNotNull null
                VaultEntry(entry.path, sha, entry.size ?: 0L, kind)
            }
        }

    /**
     * What changed between two commits, renames included. Capped by GitHub at
     * 300 files; past that the caller falls back to a full [tree].
     */
    suspend fun compare(
        base: String,
        head: String,
    ): Comparison =
        call(request(url("/compare/$base...$head")), "compare/$base...$head") { response ->
            val dto = json.decodeFromString<CompareDto>(response.body.string())
            Comparison(
                status = dto.status,
                files =
                    dto.files.map {
                        CompareChange(
                            path = it.filename,
                            status = ChangeStatus.parse(it.status),
                            sha = it.sha,
                            previousPath = it.previousFilename,
                        )
                    },
            )
        }

    /** The raw bytes of one blob. */
    suspend fun blob(sha: String): ByteArray =
        call(
            request(url("/git/blobs/$sha"), accept = "application/vnd.github.raw"),
            "blob/$sha",
        ) { it.body.bytes() }

    /**
     * One file as the branch currently has it, or null when it is not there.
     *
     * Read through the contents endpoint rather than as a blob because a write
     * has to quote the file's current sha back, and only this call hands both
     * the text and that sha over together.
     *
     * Except past a megabyte, where the endpoint still answers with the sha
     * but says `"encoding": "none"` and leaves the content empty. Decoding
     * that as base64 reads as an empty file, the edit is applied to nothing,
     * and the write replaces a whole note with one line -- quoting the right
     * sha, so GitHub accepts it. The bytes come from the blob instead.
     */
    suspend fun file(
        path: String,
        ref: String,
    ): RemoteFile? {
        val dto =
            try {
                call(request(url("/contents/${encodePath(path)}?ref=$ref")), path) { response ->
                    json.decodeFromString<ContentsDto>(response.body.string())
                }
            } catch (_: GitHubException.NotFound) {
                return null
            }
        val text =
            when {
                dto.encoding == "base64" && (dto.content.isNotBlank() || dto.size == 0L) -> decodeContent(dto)
                // Too large to be inlined: the same bytes, by their sha.
                dto.encoding == "none" || dto.content.isBlank() -> String(blob(dto.sha))
                else -> throw GitHubException.Unexpected(0, "$path came back as ${dto.encoding}")
            }
        return RemoteFile(dto.path, dto.sha, text)
    }

    /**
     * Replaces [path] with [text] in one commit.
     *
     * [sha] is the blob the edit was computed against; GitHub refuses the write
     * if the file has moved on since, which is exactly the check that makes a
     * phone editing a repository safe. The caller answers that refusal by
     * re-reading and re-applying the edit, never by forcing this one through.
     */
    suspend fun putFile(
        path: String,
        text: String,
        sha: String?,
        branch: String,
        message: String,
        author: Author,
    ): String {
        val person = PersonDto(author.name, author.email)
        val payload =
            json.encodeToString(
                PutContentsDto(
                    message = message,
                    content = Base64.getEncoder().encodeToString(text.toByteArray()),
                    branch = branch,
                    sha = sha,
                    committer = person,
                    author = person,
                ),
            )
        val request =
            Request
                .Builder()
                .url(url("/contents/${encodePath(path)}"))
                .header("Authorization", "Bearer ${config.token}")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .put(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return call(request, path) { response ->
            json.decodeFromString<CommitResultDto>(response.body.string()).commit.sha
        }
    }

    private fun decodeContent(dto: ContentsDto): String {
        // GitHub wraps the base64 at 60 columns, which the strict decoder
        // rejects outright.
        val cleaned = dto.content.filterNot { it == '\n' || it == '\r' }
        return String(Base64.getDecoder().decode(cleaned))
    }

    private companion object {
        const val BODY_PEEK = 512L
        const val DEFAULT_BACKOFF_SECONDS = 60L
        const val STALE_SHA = "does not match"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/** One file's text together with the blob sha a write has to quote back. */
data class RemoteFile(
    val path: String,
    val sha: String,
    val text: String,
)

/**
 * Percent-encodes a vault path for a URL, leaving the separators alone.
 *
 * Over a thousand paths in the vault this was written for contain a space, and
 * sixteen are Persian; handing those to a URL unencoded is not a corner case
 * here, it is the common one.
 */
internal fun encodePath(path: String): String =
    path.split('/').joinToString("/") { segment ->
        URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
    }

data class RepositoryInfo(
    val fullName: String,
    val defaultBranch: String,
    val private: Boolean,
    val pushedAt: String?,
    /**
     * Whether this token may write here.
     *
     * Asked of GitHub rather than inferred: a fine-grained token's scopes are
     * not visible from a response body, and guessing wrong in the permissive
     * direction means offering an edit that fails after it is written.
     */
    val canPush: Boolean = false,
)
