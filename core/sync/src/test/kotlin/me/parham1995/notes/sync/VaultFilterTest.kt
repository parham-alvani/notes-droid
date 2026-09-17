package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultFilterTest {
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
        assertThat(filter.kindOf("justfile")).isNull()
        assertThat(filter.kindOf("package.json")).isNull()
        assertThat(filter.kindOf("uploads/clip.mp4")).isNull()
        assertThat(filter.kindOf("noextension")).isNull()
        assertThat(filter.kindOf("")).isNull()
    }
}
