package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Bookmarks plugin's file, read. Fixtures are synthetic. */
class BookmarksTest {
    @Test
    fun `every kind is read, in order`() {
        val parsed =
            Bookmarks.parse(
                """
                {"items": [
                  {"type": "file", "ctime": 1, "path": "Projects/Plan.md"},
                  {"type": "folder", "ctime": 2, "path": "Projects", "title": "Work"},
                  {"type": "search", "ctime": 3, "query": "tag:#idea"},
                  {"type": "url", "ctime": 4, "url": "https://example.org", "title": "Example"}
                ]}
                """.trimIndent(),
            )

        assertThat(parsed)
            .containsExactly(
                Bookmark.File("Projects/Plan.md"),
                Bookmark.Folder("Projects", "Work"),
                Bookmark.Search("tag:#idea"),
                Bookmark.Url("https://example.org", "Example"),
            ).inOrder()
    }

    @Test
    fun `groups nest`() {
        val parsed =
            Bookmarks.parse(
                """
                {"items": [
                  {"type": "group", "title": "Reading", "items": [
                    {"type": "file", "path": "Books/One.md"},
                    {"type": "group", "title": "Later", "items": [
                      {"type": "file", "path": "Books/Two.md"}
                    ]}
                  ]}
                ]}
                """.trimIndent(),
            )

        assertThat(parsed)
            .containsExactly(
                Bookmark.Group(
                    "Reading",
                    listOf(
                        Bookmark.File("Books/One.md"),
                        Bookmark.Group("Later", listOf(Bookmark.File("Books/Two.md"))),
                    ),
                ),
            )
    }

    @Test
    fun `a heading is a place in a note, and a block is only the note`() {
        val parsed =
            Bookmarks.parse(
                """
                {"items": [
                  {"type": "file", "path": "Guide.md", "subpath": "#Setup"},
                  {"type": "file", "path": "Guide.md", "subpath": "#Setup#On a phone"},
                  {"type": "file", "path": "Guide.md", "subpath": "#^abc123"},
                  {"type": "heading", "path": "Old.md", "subpath": "#Legacy"}
                ]}
                """.trimIndent(),
            )

        assertThat(parsed)
            .containsExactly(
                Bookmark.File("Guide.md", "Setup"),
                Bookmark.File("Guide.md", "On a phone"),
                Bookmark.File("Guide.md"),
                Bookmark.File("Old.md", "Legacy"),
            ).inOrder()
    }

    @Test
    fun `a label falls back to what the bookmark points at`() {
        assertThat(Bookmark.File("Projects/Plan.md").label).isEqualTo("Plan")
        assertThat(Bookmark.File("Projects/Plan.md", "Goals").label).isEqualTo("Plan › Goals")
        assertThat(Bookmark.File("Projects/Plan.md", title = "The plan").label).isEqualTo("The plan")
        assertThat(Bookmark.Folder("Projects/Archive").label).isEqualTo("Archive")
        assertThat(Bookmark.Search("red harvest", title = "").label).isEqualTo("red harvest")
    }

    @Test
    fun `the vault root is a folder too`() {
        assertThat(Bookmarks.parse("""{"items":[{"type":"folder","path":"/"}]}"""))
            .containsExactly(Bookmark.Folder(""))
    }

    @Test
    fun `what is not understood is skipped rather than fatal`() {
        val parsed =
            Bookmarks.parse(
                """
                {"items": [
                  {"type": "graph", "path": "x"},
                  {"type": "file"},
                  "not an object",
                  {"type": "file", "path": "Kept.md", "futureField": {"a": 1}}
                ], "version": 99}
                """.trimIndent(),
            )

        assertThat(parsed).containsExactly(Bookmark.File("Kept.md"))
        assertThat(Bookmarks.parse("[]")).isEmpty()
        assertThat(Bookmarks.parse("{}")).isEmpty()
    }
}
