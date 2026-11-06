package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Base64

/** A note's history over GitHub's API, across a rename, as `git log --follow` lists it. Synthetic. */
class RestNoteHistoryTest {
    private lateinit var server: MockWebServer
    private lateinit var history: RestNoteHistory

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
                limiter = RateLimiter(permitsPerSecond = 1_000_000),
            )
        history = RestNoteHistory(client, branch = "main")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(body: String) =
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(body)
                .build(),
        )

    private fun commit(
        sha: String,
        message: String,
        date: String,
        parent: String? = null,
        files: String = "",
    ) = """{"sha":"$sha","commit":{"author":{"name":"A Person","email":"a@example.com","date":"$date"},""" +
        """"message":"$message"},"parents":[${parent?.let { """{"sha":"$it"}""" }.orEmpty()}]""" +
        (if (files.isEmpty()) "" else ""","files":[$files]""") + "}"

    @Test
    fun `the history follows a rename and reads each version from where it was`() =
        runTest {
            respond(
                "[" + commit("c3", "docs: edit\\n\\nmore", "2026-03-03T10:00:00Z", "c2") + "," +
                    commit("c2", "docs: rename", "2026-02-02T10:00:00Z", "c1") + "]",
            )
            respond(
                commit(
                    "c2",
                    "docs: rename",
                    "2026-02-02T10:00:00Z",
                    "c1",
                    """{"filename":"Notes/New.md","status":"renamed","previous_filename":"Old.md"}""",
                ),
            )
            respond("[" + commit("c1", "docs: add", "2026-01-01T10:00:00Z") + "]")
            respond(
                commit("c1", "docs: add", "2026-01-01T10:00:00Z", files = """{"filename":"Old.md","status":"added"}"""),
            )

            val versions = history.history("Notes/New.md")

            assertThat(versions.map { it.commit }).containsExactly("c3", "c2", "c1").inOrder()
            assertThat(versions.map { it.path }).containsExactly("Notes/New.md", "Notes/New.md", "Old.md").inOrder()
            assertThat(versions.first().subject).isEqualTo("docs: edit")
            assertThat(versions.first().author).isEqualTo("A Person")
            assertThat(versions.first().date).isEqualTo(Instant.parse("2026-03-03T10:00:00Z"))
            // The listing under the old name goes on from the commit before the rename.
            server.takeRequest()
            server.takeRequest()
            val older = server.takeRequest()
            assertThat(older.url.queryParameter("path")).isEqualTo("Old.md")
            assertThat(older.url.queryParameter("sha")).isEqualTo("c1")

            val text = Base64.getEncoder().encodeToString("as it was\n".toByteArray())
            respond("""{"path":"Old.md","sha":"blob","size":10,"content":"$text","encoding":"base64"}""")
            assertThat(history.content(versions.last())).isEqualTo("as it was\n")
            server.takeRequest()
            val read = server.takeRequest()
            assertThat(read.url.encodedPath).endsWith("/contents/Old.md")
            assertThat(read.url.queryParameter("ref")).isEqualTo("c1")
        }

    @Test
    fun `a note never renamed is one listing`() =
        runTest {
            respond("[" + commit("c1", "docs: add", "2026-01-01T10:00:00Z") + "]")
            respond(
                commit("c1", "docs: add", "2026-01-01T10:00:00Z", files = """{"filename":"A.md","status":"added"}"""),
            )

            assertThat(history.history("A.md").map { it.commit }).containsExactly("c1")
        }
}
