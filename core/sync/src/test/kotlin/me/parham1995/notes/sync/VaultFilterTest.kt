package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultFilterTest {
    @Test
    fun `a walk skips directories that can hold no vault content`() {
        val filter = VaultFilter()

        assertThat(filter.mayContain("notes")).isTrue()
        assertThat(filter.mayContain("notes/deep")).isTrue()
        assertThat(filter.mayContain("node_modules")).isFalse()
        assertThat(filter.mayContain(".github")).isFalse()
        // Except on the way to the one file under .obsidian that is content.
        assertThat(filter.mayContain(".obsidian")).isTrue()
        assertThat(filter.mayContain(".obsidian/plugins/iconic")).isTrue()
        assertThat(filter.mayContain(".obsidian/themes")).isFalse()
    }

    private val filter = VaultFilter()

    @Test
    fun `markdown anywhere in the tree is vault content`() {
        assertThat(filter.kindOf("Note.md")).isEqualTo(BlobKind.MARKDOWN)
        assertThat(filter.kindOf("alpha/beta/gamma/Deep Note.md")).isEqualTo(BlobKind.MARKDOWN)
    }

    @Test
    fun `spaces and non-ascii in names are ordinary`() {
        assertThat(filter.kindOf("alpha/A Note With Spaces.md")).isEqualTo(BlobKind.MARKDOWN)
        assertThat(filter.kindOf("alpha/Ünïcödé Nøte.md")).isEqualTo(BlobKind.MARKDOWN)
    }

    @Test
    fun `dot directories are tooling, never notes`() {
        assertThat(filter.kindOf(".config/settings.md")).isNull()
        assertThat(filter.kindOf("alpha/.hidden/Note.md")).isNull()
        assertThat(filter.kindOf(".github/workflows/ci.yaml")).isNull()
    }

    @Test
    fun `excluded roots are skipped wholesale`() {
        assertThat(filter.kindOf("node_modules/pkg/readme.md")).isNull()
        // Only at the root; a note that merely shares the name is still a note.
        assertThat(filter.kindOf("alpha/node_modules.md")).isEqualTo(BlobKind.MARKDOWN)
    }

    @Test
    fun `images are recognised and case does not matter`() {
        assertThat(filter.kindOf("uploads/pic.jpg")).isEqualTo(BlobKind.IMAGE)
        assertThat(filter.kindOf("uploads/PIC.JPG")).isEqualTo(BlobKind.IMAGE)
        assertThat(filter.kindOf("uploads/diagram.svg")).isEqualTo(BlobKind.IMAGE)
    }

    @Test
    fun `images can be excluded entirely`() {
        val noImages = VaultFilter(includeImages = false)
        assertThat(noImages.kindOf("uploads/pic.jpg")).isNull()
        assertThat(noImages.kindOf("Note.md")).isEqualTo(BlobKind.MARKDOWN)
    }

    @Test
    fun `everything else is ignored`() {
        assertThat(filter.kindOf("notes/diagram.excalidraw")).isNull()
        assertThat(filter.kindOf("notes/data.sqlite")).isNull()
        assertThat(filter.kindOf("noextension")).isNull()
        assertThat(filter.kindOf("")).isNull()
    }

    @Test
    fun `the icon assignments come through despite living under a dot-directory`() {
        assertThat(filter.kindOf(VaultFilter.ICONIC_CONFIG)).isEqualTo(BlobKind.CONFIG)
    }

    @Test
    fun `so do the bookmarks and the daily notes settings`() {
        assertThat(filter.kindOf(".obsidian/bookmarks.json")).isEqualTo(BlobKind.CONFIG)
        assertThat(filter.kindOf(".obsidian/daily-notes.json")).isEqualTo(BlobKind.CONFIG)
        // And nothing beside them: the rest of the editor's settings stay out.
        assertThat(filter.kindOf(".obsidian/workspace.json")).isNull()
        assertThat(filter.kindOf(".obsidian/bookmarks.json.bak")).isNull()
        assertThat(filter.kindOf("notes/.obsidian/bookmarks.json")).isNull()
    }

    @Test
    fun `the exception is that one file and does not open up its directory`() {
        assertThat(filter.kindOf(".obsidian/plugins/iconic/manifest.json")).isNull()
        assertThat(filter.kindOf(".obsidian/plugins/iconic/data.json.bak")).isNull()
        assertThat(filter.kindOf(".obsidian/app.json")).isNull()
        assertThat(filter.kindOf(".obsidian/plugins/other/data.json")).isNull()
        // Not a prefix match either: a note that happens to be named this way
        // inside the vault proper is still a note.
        assertThat(filter.kindOf("notes/.obsidian/plugins/iconic/data.json")).isNull()
    }

    @Test
    fun `attachments are recorded so they can be opened later`() {
        assertThat(filter.kindOf("uploads/manual.pdf")).isEqualTo(BlobKind.OTHER)
        assertThat(filter.kindOf("uploads/clip.mp4")).isEqualTo(BlobKind.OTHER)
        assertThat(filter.kindOf("uploads/voice.ogg")).isEqualTo(BlobKind.OTHER)
        assertThat(filter.kindOf("uploads/sheet.xlsx")).isEqualTo(BlobKind.OTHER)
        // Case is as irrelevant here as it is for images.
        assertThat(filter.kindOf("uploads/MANUAL.PDF")).isEqualTo(BlobKind.OTHER)
    }

    @Test
    fun `the source of tooling checked in beside the notes is still ignored`() {
        // A vault repository often carries the plugins it uses. None of it is
        // something a reader can open, and all of it would be manifest rows.
        assertThat(filter.kindOf("some-plugin/src/main.ts")).isNull()
        assertThat(filter.kindOf("some-plugin/manifest.json")).isNull()
        assertThat(filter.kindOf("some-plugin/esbuild.config.mjs")).isNull()
        assertThat(filter.kindOf("package.json")).isNull()
        assertThat(filter.kindOf("justfile")).isNull()
    }
}
