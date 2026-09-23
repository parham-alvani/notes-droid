package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The client against a server that answers the way GitHub does, including the
 * ways that are easy to misread.
 */
class GitHubClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            GitHubClient(
                config =
                    GitHubConfig(
                        owner = "owner",
                        repo = "repo",
                        token = "test-token",
                        apiBase = server.url("/").toString().trimEnd('/'),
                    ),
                http = OkHttpClient(),
                limiter = RateLimiter(permitsPerSecond = 1_000_000),
            )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(
        body: String,
        code: Int = 200,
        vararg headers: Pair<String, String>,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .body(body)
            .build(),
    )

    private companion object {
        const val SLOW_SECONDS = 10L
        const val REQUEST_STARTS_MS = 300L
    }

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun `a file too large to inline is read from its blob, not taken as empty`() =
        runTest {
            val big = "# Journal\n\n" + "a line that has been there a while\n".repeat(40_000)
            // What the contents endpoint says for anything over a megabyte.
            respond(
                """{"path":"Journal.md","sha":"sha-big","size":${big.length},"content":"","encoding":"none"}""",
            )
            respond(big)

            val file = client.file("Journal.md", "main")

            assertThat(file!!.sha).isEqualTo("sha-big")
            assertThat(file.text).isEqualTo(big)
            server.takeRequest()
            val blob = server.takeRequest()
            assertThat(blob.url.encodedPath).endsWith("/git/blobs/sha-big")
        }

    @Test
    fun `an edit to a large file keeps what the file already said`() =
        runTest {
            val big = "- one\n".repeat(200_000)
            respond(
                """{"path":"Scratch.md","sha":"sha-big","size":${big.length},"content":"","encoding":"none"}""",
            )
            respond(big)
            respond("""{"commit":{"sha":"c1"}}""", code = 201)

            val writer = RestVaultWriter(client, branch = "main")
            val outcome =
                writer.write("Scratch.md", "msg", Author("A", "a@example.com")) { current ->
                    current.orEmpty() + "- two\n"
                }

            assertThat(outcome).isInstanceOf(WriteOutcome.Written::class.java)
            assertThat((outcome as WriteOutcome.Written).text).isEqualTo(big + "- two\n")
        }

    @Test
    fun `a cancelled request stops rather than running to the end`() =
        runTest {
            // A server that takes its time, the way a forty-megabyte blob
            // does on a phone.
            server.enqueue(
                MockResponse
                    .Builder()
                    .headersDelay(SLOW_SECONDS, TimeUnit.SECONDS)
                    .body("late")
                    .build(),
            )

            val elapsed =
                withContext(Dispatchers.Default) {
                    val started = System.nanoTime()
                    val request = launch { client.blob("sha-slow") }
                    delay(REQUEST_STARTS_MS)
                    request.cancelAndJoin()
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                }

            // A blocking execute() cannot be interrupted, and held the
            // coroutine until the server answered.
            assertThat(elapsed).isLessThan(SLOW_SECONDS / 2)
        }

    @Test
    fun `an unchanged head answers from the etag and costs nothing`() =
        runTest {
            respond("""{"ref":"refs/heads/main","object":{"sha":"head1","type":"commit"}}""", 200, "etag" to "\"e1\"")
            server.enqueue(MockResponse.Builder().code(304).build())

            val first = client.head("main")
            val second = client.head("main", etag = (first as Conditional.Fresh).etag)

            assertThat(first.value).isEqualTo("head1")
            assertThat(first.etag).isEqualTo("\"e1\"")
            assertThat(second).isEqualTo(Conditional.NotModified)
            server.takeRequest()
            assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("\"e1\"")
        }

    @Test
    fun `a stale sha answered with 422 is re-read and the edit applied again`() =
        runTest {
            // Read at one sha; the desk commits in between; the write quoting
            // the old sha is refused with 422, which GitHub uses as often as
            // 409 for this.
            respond("""{"path":"a.md","sha":"sha-1","size":6,"content":"${base64("- one\n")}"}""")
            respond("""{"message":"a.md does not match sha-1"}""", code = 422)
            respond("""{"path":"a.md","sha":"sha-2","size":12,"content":"${base64("- one\n- two\n")}"}""")
            respond("""{"commit":{"sha":"c2"}}""", code = 200)

            val outcome =
                RestVaultWriter(client, branch = "main")
                    .write("a.md", "msg", Author("A", "a@example.com")) { current -> current.orEmpty() + "- three\n" }

            // Applied to what the file says now, not forced over it.
            assertThat((outcome as WriteOutcome.Written).text).isEqualTo("- one\n- two\n- three\n")
            assertThat(server.requestCount).isEqualTo(4)
        }

    @Test
    fun `a 422 that is not about the sha is not taken for a conflict`() =
        runTest {
            respond("""{"message":"Invalid request. content is not valid Base64"}""", code = 422)

            val failure =
                runCatching { client.putFile("a.md", "x", "sha-1", "main", "msg", Author("A", "a@example.com")) }
                    .exceptionOrNull()

            assertThat(failure).isInstanceOf(GitHubException.Unexpected::class.java)
        }

    @Test
    fun `a secondary limit without retry-after is a reason to wait, not a refusal`() =
        runTest {
            respond(
                """{"message":"You have exceeded a secondary rate limit. Please wait a few minutes."}""",
                code = 403,
            )

            val failure = runCatching { client.blob("sha-a") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(GitHubException.SlowDown::class.java)
        }

    @Test
    fun `a 403 that is about permission stays a refusal`() =
        runTest {
            respond("""{"message":"Resource not accessible by personal access token"}""", code = 403)

            val failure = runCatching { client.blob("sha-a") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(GitHubException.Forbidden::class.java)
        }

    @Test
    fun `an exhausted primary limit says when it lifts`() =
        runTest {
            respond(
                """{"message":"API rate limit exceeded"}""",
                403,
                "x-ratelimit-remaining" to "0",
                "x-ratelimit-reset" to "1900000000",
            )

            val failure = runCatching { client.blob("sha-a") }.exceptionOrNull()

            assertThat((failure as GitHubException.RateLimited).resetEpochSeconds).isEqualTo(1_900_000_000L)
        }

    @Test
    fun `an ordinary file decodes from the wrapped base64`() =
        runTest {
            val encoded = base64("hello\nworld\n").chunked(4).joinToString("\n")
            respond("""{"path":"a.md","sha":"sha-a","size":12,"content":"$encoded","encoding":"base64"}""")

            assertThat(client.file("a.md", "main")!!.text).isEqualTo("hello\nworld\n")
        }
}
