package me.parham1995.notes.ui.render

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.ui.LocalReading
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `remote-images.md` in notes-linux's `docs/specs/`, through the real
 * renderer: with Pictures from the web off, a picture from a site is a
 * placeholder naming the site until it is tapped, and the tap is what puts
 * the picture there. Synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class RemoteImageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        markdown: String,
        remoteImages: Boolean,
    ) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        compose.setContent {
            CompositionLocalProvider(LocalReading provides ReadingSettings(remoteImages = remoteImages)) {
                blocks.forEach { MdBlockView(it, RenderActions(), emptySet()) }
            }
        }
    }

    @Test
    fun `off, a picture from the web is its site and its words until tapped`() {
        render("![a chart](https://x.org/a.png)", remoteImages = false)

        compose.onNodeWithText("x.org").assertExists()
        compose.onNodeWithText("a chart").assertExists()
        compose.onNodeWithContentDescription("a chart").assertDoesNotExist()

        compose.onNodeWithText("x.org").performClick()

        compose.onNodeWithText("x.org").assertDoesNotExist()
        compose.onNodeWithContentDescription("a chart").assertExists()
    }

    @Test
    fun `on, a picture from the web is drawn`() {
        render("![a chart](https://x.org/a.png)", remoteImages = true)

        compose.onNodeWithContentDescription("a chart").assertExists()
        compose.onNodeWithText("x.org").assertDoesNotExist()
    }

    @Test
    fun `off, an HTML picture from the web is a placeholder too, and a data picture is drawn`() {
        render(
            "<img src=\"HTTP://X.ORG/a.png\" width=\"20\" alt=\"logo\">\n\n![dot](data:image/png;base64,AAAA)",
            remoteImages = false,
        )

        compose.onNodeWithText("x.org").assertExists()
        compose.onNodeWithContentDescription("logo").assertDoesNotExist()
        compose.onNodeWithContentDescription("dot").assertExists()
    }
}
