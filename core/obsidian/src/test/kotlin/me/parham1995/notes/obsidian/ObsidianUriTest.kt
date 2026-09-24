package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `obsidian://` links as another app hands them over. Names are synthetic. */
class ObsidianUriTest {
    @Test
    fun `open names a vault and a file, both encoded`() {
        assertThat(ObsidianUri.parse("obsidian://open?vault=My%20Notes&file=Projects%2FGarden%20Plan"))
            .isEqualTo(ObsidianLink.Open("My Notes", "Projects/Garden Plan"))
    }

    @Test
    fun `the file keeps an extension it was given`() {
        assertThat(ObsidianUri.parse("obsidian://open?vault=v&file=Inbox.md"))
            .isEqualTo(ObsidianLink.Open("v", "Inbox.md"))
    }

    @Test
    fun `a heading rides along after a hash, and a block is dropped`() {
        assertThat(ObsidianUri.parse("obsidian://open?vault=v&file=Guide%23Setup"))
            .isEqualTo(ObsidianLink.Open("v", "Guide", "Setup"))
        assertThat(ObsidianUri.parse("obsidian://open?vault=v&file=Guide%23%5Eab12"))
            .isEqualTo(ObsidianLink.Open("v", "Guide"))
    }

    @Test
    fun `a plus is a plus, not a space`() {
        assertThat(ObsidianUri.parse("obsidian://open?vault=v&file=C++%20notes"))
            .isEqualTo(ObsidianLink.Open("v", "C++ notes"))
    }

    @Test
    fun `non-latin names come through`() {
        assertThat(
            ObsidianUri.parse(
                "obsidian://open?vault=%D8%AF%D9%81%D8%AA%D8%B1&file=%DB%8C%D8%A7%D8%AF%D8%AF%D8%A7%D8%B4%D8%AA",
            ),
        ).isEqualTo(ObsidianLink.Open("دفتر", "یادداشت"))
    }

    @Test
    fun `a vault alone opens the vault`() {
        assertThat(ObsidianUri.parse("obsidian://open?vault=v")).isEqualTo(ObsidianLink.Open("v"))
    }

    @Test
    fun `an absolute path means nothing on a phone`() {
        assertThat(ObsidianUri.parse("obsidian://open?path=%2FUsers%2Fsomeone%2Fvault%2FNote.md")).isNull()
        assertThat(ObsidianUri.parse("obsidian://open?vault=v&path=%2Fsomewhere%2FNote.md"))
            .isEqualTo(ObsidianLink.Open("v"))
    }

    @Test
    fun `search carries its query`() {
        assertThat(ObsidianUri.parse("obsidian://search?vault=v&query=red%20harvest"))
            .isEqualTo(ObsidianLink.Search("v", "red harvest"))
        assertThat(ObsidianUri.parse("obsidian://search?query=path%3AGarden"))
            .isEqualTo(ObsidianLink.Search(null, "path:Garden"))
    }

    @Test
    fun `the shorthand form names the vault in the path`() {
        assertThat(ObsidianUri.parse("obsidian://vault/My%20Notes/Projects/Plan"))
            .isEqualTo(ObsidianLink.Open("My Notes", "Projects/Plan"))
        assertThat(ObsidianUri.parse("obsidian://vault/Notes")).isEqualTo(ObsidianLink.Open("Notes"))
    }

    @Test
    fun `anything else is not followed`() {
        assertThat(ObsidianUri.parse("https://example.org/open?vault=v")).isNull()
        assertThat(ObsidianUri.parse("obsidian://new?vault=v&name=x")).isNull()
        assertThat(ObsidianUri.parse("obsidian://")).isNull()
        assertThat(ObsidianUri.parse("obsidian://open")).isNull()
        // Broken encoding is read as written rather than thrown.
        assertThat(ObsidianUri.parse("obsidian://open?vault=v&file=100%")).isEqualTo(ObsidianLink.Open("v", "100%"))
    }
}
