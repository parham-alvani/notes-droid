package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `remote-images.md` in notes-linux's `docs/specs/`: which pictures of a note
 * ask a site for themselves. The page's table, row for row, on the blocks
 * this app draws -- the renderer turns a remote [MdBlock.Image] into the
 * placeholder when the setting is off (`RemoteImageTest` in the app).
 * Synthetic.
 */
class RemoteImagesTest {
    private fun blocks(markdown: String) = MarkdownParser.parseNote(markdown).blocks

    private fun image(markdown: String) = blocks(markdown).filterIsInstance<MdBlock.Image>().single()

    @Test
    fun `a picture from the web is remote, and names its site`() {
        val picture = image("![a](https://x.org/a.png)")
        assertThat(picture.path).isEqualTo("https://x.org/a.png")
        assertThat(picture.alt).isEqualTo("a")
        assertThat(RemoteImages.isRemote(picture.path)).isTrue()
        assertThat(RemoteImages.host(picture.path)).isEqualTo("x.org")
    }

    @Test
    fun `a picture from the vault is not`() {
        val picture = image("![[a.png]]")
        assertThat(picture.path).isEqualTo("a.png")
        assertThat(RemoteImages.isRemote(picture.path)).isFalse()
    }

    @Test
    fun `a data picture is not`() {
        val picture = image("![a](data:image/png;base64,AAAA)")
        assertThat(RemoteImages.isRemote(picture.path)).isFalse()
    }

    @Test
    fun `an HTML picture in capitals is remote, and keeps its width`() {
        val picture = image("<img src=\"HTTP://X.ORG/a.png\" width=\"20\">")
        assertThat(picture.path).isEqualTo("HTTP://X.ORG/a.png")
        assertThat(picture.width).isEqualTo(20)
        assertThat(RemoteImages.isRemote(picture.path)).isTrue()
        assertThat(RemoteImages.host(picture.path)).isEqualTo("x.org")
    }

    @Test
    fun `a link stays a link`() {
        val blocks = blocks("[link](https://x.org)")
        assertThat(blocks.filterIsInstance<MdBlock.Image>()).isEmpty()
        val paragraph = blocks.filterIsInstance<MdBlock.Paragraph>().single()
        assertThat(paragraph.inlines.single()).isEqualTo(MdInline.Link("https://x.org", listOf(MdInline.Text("link"))))
    }

    @Test
    fun `a picture inside a link is no picture to fetch`() {
        // Drawn as the link it is, with the picture's words; nothing is asked of the site.
        val blocks = blocks("[![badge](https://x.org/b.svg)](https://x.org)")
        assertThat(blocks.filterIsInstance<MdBlock.Image>()).isEmpty()
    }

    @Test
    fun `a centred HTML picture is a picture, and HTML with words stays words`() {
        val picture = image("<p align=\"center\"><img src=\"uploads/a.png\" alt=\"A\" height=\"40px\"></p>")
        assertThat(picture.path).isEqualTo("uploads/a.png")
        assertThat(picture.alt).isEqualTo("A")
        assertThat(picture.height).isEqualTo(40)
        assertThat(
            blocks("<div>words <img src=\"https://x.org/a.png\"></div>").filterIsInstance<MdBlock.Image>(),
        ).isEmpty()
    }
}
