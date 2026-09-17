package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class RestVaultSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var sync: RestVaultSync

    private val recorded = RecordingSink()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client =
            GitHubClient(
                config =
                    GitHubConfig(
                        owner = "owner",
                        repo = "repo",
                        token = "test-token",
                        apiBase = server.url("/").toString().trimEnd('/'),
                    ),
                http = OkHttpClient(),
                // No pacing in tests; RateLimiter has its own.
                limiter = RateLimiter(permitsPerSecond = 1_000_000),
            )
        sync = RestVaultSync(client, branch = "main")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(
        body: String,
        code: Int = 200,
        etag: String? = null,
    ) = MockResponse
        .Builder()
        .code(code)
        .apply { etag?.let { addHeader("etag", it) } }
        .body(body)
        .build()

    @Test
    fun `an unchanged vault costs exactly one request`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(304).build())

            val base = SyncBase(commit = "head1", manifest = mapOf("a.md" to "sha-a"), etagRef = "\"etag1\"")
            val plan = sync.plan(base)

            assertThat(plan.isEmpty).isTrue()
            assertThat(plan.headCommit).isEqualTo("head1")
            // The whole point of the ETag path: one request, and GitHub does not
            // even bill a 304 against the rate limit.
            assertThat(server.requestCount).isEqualTo(1)

            val request = server.takeRequest()
            assertThat(request.url.encodedPath).endsWith("/git/ref/heads/main")
            assertThat(request.headers["If-None-Match"]).isEqualTo("\"etag1\"")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-token")
        }

    @Test
    fun `head unchanged without an etag still plans nothing`() =
        runTest {
            server.enqueue(json("""{"ref":"refs/heads/main","object":{"sha":"head1","type":"commit"}}"""))

            val plan = sync.plan(SyncBase("head1", mapOf("a.md" to "sha-a")))

            assertThat(plan.isEmpty).isTrue()
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `a first sync lists the tree and downloads the markdown`() =
        runTest {
            server.enqueue(json("""{"ref":"refs/heads/main","object":{"sha":"head1","type":"commit"}}"""))
            server.enqueue(
                json(
                    """
                    {"sha":"tree1","truncated":false,"tree":[
                      {"path":"a.md","mode":"100644","type":"blob","sha":"sha-a","size":10},
                      {"path":"uploads/pic.jpg","mode":"100644","type":"blob","sha":"sha-p","size":900},
                      {"path":".github/ci.yaml","mode":"100644","type":"blob","sha":"sha-c","size":5},
                      {"path":"alpha","mode":"040000","type":"tree","sha":"sha-t"}
                    ]}
                    """.trimIndent(),
                ),
            )

            val plan = sync.plan(SyncBase(commit = null, manifest = emptyMap()))

            // The image is planned, the CI config and the tree entry are not.
            assertThat(plan.adds.map { it.path }).containsExactly("a.md", "uploads/pic.jpg")

            server.enqueue(json("note body"))
            sync.apply(plan, recorded)

            // Markdown is fetched; the image is only recorded, so a default
            // install carries the notes alone.
            assertThat(recorded.written.keys).containsExactly("a.md")
            assertThat(recorded.written["a.md"]).isEqualTo("note body")
            assertThat(recorded.recorded).containsExactly("uploads/pic.jpg" to LocalState.ABSENT)
        }

    @Test
    fun `a moved note is applied without downloading it again`() =
        runTest {
            server.enqueue(json("""{"ref":"refs/heads/main","object":{"sha":"head2","type":"commit"}}"""))
            server.enqueue(
                json(
                    """
                    {"files":[
                      {"filename":"beta/note.md","status":"renamed",
                       "sha":"sha-x","previous_filename":"alpha/note.md"}
                    ]}
                    """.trimIndent(),
                ),
            )

            val base = SyncBase("head1", mapOf("alpha/note.md" to "sha-x"))
            val plan = sync.plan(base)
            sync.apply(plan, recorded)

            assertThat(recorded.moved).containsExactly("alpha/note.md" to "beta/note.md")
            assertThat(recorded.written).isEmpty()
            // ref + compare only: the blob endpoint is never touched.
            assertThat(server.requestCount).isEqualTo(2)
        }

    @Test
    fun `an unreachable base commit falls back to a full tree listing`() =
        runTest {
            server.enqueue(json("""{"ref":"refs/heads/main","object":{"sha":"head9","type":"commit"}}"""))
            // Force-pushed: the old commit is gone, so compare 404s.
            server.enqueue(json("""{"message":"Not Found"}""", code = 404))
            server.enqueue(
                json(
                    """
                    {"sha":"tree9","truncated":false,"tree":[
                      {"path":"a.md","mode":"100644","type":"blob","sha":"sha-a2","size":10}
                    ]}
                    """.trimIndent(),
                ),
            )

            val plan = sync.plan(SyncBase("gone-commit", mapOf("a.md" to "sha-a1")))

            assertThat(plan.modifies.map { it.path }).containsExactly("a.md")
            assertThat(server.requestCount).isEqualTo(3)
        }

    @Test
    fun `a rejected token is reported as such`() =
        runTest {
            server.enqueue(json("""{"message":"Bad credentials"}""", code = 401))

            val failure =
                runCatching { sync.plan(SyncBase(null, emptyMap())) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(GitHubException.Unauthorized::class.java)
        }

    @Test
    fun `a truncated tree is refused rather than treated as complete`() =
        runTest {
            server.enqueue(json("""{"ref":"refs/heads/main","object":{"sha":"head1","type":"commit"}}"""))
            server.enqueue(json("""{"sha":"t","truncated":true,"tree":[]}"""))

            val failure =
                runCatching { sync.plan(SyncBase(null, emptyMap())) }.exceptionOrNull()

            // Accepting it would look exactly like a vault whose notes vanished.
            assertThat(failure).isInstanceOf(GitHubException.TreeTruncated::class.java)
        }

    private class RecordingSink : VaultSink {
        val written = linkedMapOf<String, String>()
        val recorded = mutableListOf<Pair<String, LocalState>>()
        val moved = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()

        override suspend fun write(
            path: String,
            bytes: ByteArray,
            sha: String,
        ) {
            written[path] = bytes.decodeToString()
        }

        override suspend fun record(
            entry: VaultEntry,
            state: LocalState,
        ) {
            recorded += entry.path to state
        }

        override suspend fun move(
            from: String,
            to: String,
        ) {
            moved += from to to
        }

        override suspend fun delete(path: String) {
            deleted += path
        }
    }
}
