package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64

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
    fun `an ordinary file decodes from the wrapped base64`() =
        runTest {
            val encoded = base64("hello\nworld\n").chunked(4).joinToString("\n")
            respond("""{"path":"a.md","sha":"sha-a","size":12,"content":"$encoded","encoding":"base64"}""")

            assertThat(client.file("a.md", "main")!!.text).isEqualTo("hello\nworld\n")
        }
}
