package me.parham1995.notes.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

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
        return GitHubException.SlowDown(DEFAULT_BACKOFF_SECONDS)
    }

    /** Confirms the token works and the repository is visible. */
    suspend fun repository(): RepositoryInfo =
        call(request(url("")), "${config.owner}/${config.repo}") { response ->
            val dto = json.decodeFromString<RepoDto>(response.body.string())
            RepositoryInfo(dto.fullName, dto.defaultBranch, dto.private, dto.pushedAt)
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
    ): List<CompareChange> =
        call(request(url("/compare/$base...$head")), "compare/$base...$head") { response ->
            val dto = json.decodeFromString<CompareDto>(response.body.string())
            dto.files.map {
                CompareChange(
                    path = it.filename,
                    status = ChangeStatus.parse(it.status),
                    sha = it.sha,
                    previousPath = it.previousFilename,
                )
            }
        }

    /** The raw bytes of one blob. */
    suspend fun blob(sha: String): ByteArray =
        call(
            request(url("/git/blobs/$sha"), accept = "application/vnd.github.raw"),
            "blob/$sha",
        ) { it.body.bytes() }

    private companion object {
        const val BODY_PEEK = 512L
        const val DEFAULT_BACKOFF_SECONDS = 60L
    }
}

data class RepositoryInfo(
    val fullName: String,
    val defaultBranch: String,
    val private: Boolean,
    val pushedAt: String?,
)
