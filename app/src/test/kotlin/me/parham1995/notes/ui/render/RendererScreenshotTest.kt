package me.parham1995.notes.ui.render

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import kotlinx.coroutines.runBlocking
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.LocalLucide
import me.parham1995.notes.ui.icon.LucideSet
import me.parham1995.notes.ui.theme.NotesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What the renderer draws, compared pixel for pixel with what it drew last time.
 *
 * The semantics tests around this one ask where things are and what they say;
 * none of them can see a block painted over another, a heading's rule in the
 * wrong colour, a chevron that grew to the height of its line, a Persian
 * paragraph set left-to-right. Those are the bugs this app has shipped, and a
 * picture is the assertion that catches them.
 *
 * The goldens live in `app/src/test/screenshots/`. `just test` compares against
 * them and fails with the diff under `app/build/outputs/roborazzi/`; `just
 * screenshots-record` redraws them once a change is meant. They are recorded on
 * macOS and checked on Linux, so a pixel may drift in colour by up to
 * [MAX_PIXEL_DISTANCE] -- Robolectric's native renderer is the same on both,
 * but it is not promised to be identical to the pixel -- while no pixel may
 * change outright.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Fixture markdown, all of it made up: a heading of every weight that
     * matters, prose with the inline marks, three task states, a section that
     * is folded away, and a callout.
     */
    private val note =
        """
        # Weekly review

        Plain prose with **bold**, *italic*, `code`, a [[Garden plan]] and a #tag.

        ## Open

        - [ ] Water the plants 📅 2026-10-03
        - [x] Sharpen the pencils ✅ 2026-10-01
        - [>] Call the library

        ## Folded away

        This paragraph must not be on the page.

        ## Notes

        > [!tip] A callout
        > With a body of its own.
        """.trimIndent()

    private val blocks =
        """
        ## Table

        | Plant | Water | Light |
        | --- | --- | --- |
        | Fern | Daily | Shade |
        | Cactus | Monthly | Full sun |

        ## Code

        ```yaml
        # watering
        plant: fern
        every: 1
        unit: "day"
        ```

        > A quotation, with a second line
        > under it.
        """.trimIndent()

    private val persian =
        """
        ## یادداشت

        این یک پاراگراف فارسی است که باید از راست به چپ و با قلم وزیرمتن چیده شود.

        The Latin paragraph after it reads left to right again.

        - [ ] کتاب را برگردان 📅 2026-10-03
        """.trimIndent()

    @Test
    fun `a note, dark`() {
        page { Note(note) }
        capture("note_dark")
    }

    @Test
    fun `a note, light`() {
        page(theme = ThemeChoice.LIGHT) { Note(note) }
        capture("note_light")
    }

    @Test
    fun `a table, a code block and a quote`() {
        page { Note(blocks) }
        awaitHighlight("plant: fern")
        capture("blocks")
    }

    /**
     * A code block's colours arrive from another thread, after the first
     * frame, and `waitForIdle` does not know to wait for them. The picture
     * does, or it would be of whichever frame happened to be drawn.
     *
     * The fence is yaml, one of the grammars this app carries itself: the
     * `highlights` library behind the other languages is Java 21 bytecode and
     * cannot be loaded by the JDK 17 these tests run on.
     */
    private fun awaitHighlight(code: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose
                .onNodeWithText(code, substring = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .any { it.spanStyles.isNotEmpty() }
        }
    }

    @Test
    fun `persian prose reads right to left`() {
        page { Note(persian) }
        capture("persian")
    }

    @Test
    fun `the note screen's top bar`() {
        page {
            TopAppBar(
                title = { Text("Weekly review") },
                navigationIcon = {
                    // The same shape as the real one: a Row, because the slot
                    // is a box and draws two buttons on top of each other.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {}) { Icon(Icons.Filled.Menu, contentDescription = "files") }
                        IconButton(onClick = {}) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Search, contentDescription = "search") }
                },
            )
        }
        capture("top_bar")
    }

    /**
     * A note the way the note screen shows one: the real document, with every
     * level-two heading foldable and the one called "Folded away" folded.
     */
    @Composable
    private fun Note(markdown: String) {
        val parsed = MarkdownParser.parseNote(markdown).blocks
        val folded =
            parsed
                .withIndex()
                .filter { (_, block) -> block is MdBlock.Heading && block.text == "Folded away" }
                .map { it.index }
                .toSet()
        MarkdownDocument(
            blocks = parsed,
            actions = RenderActions(),
            folded = folded,
            onToggleFold = {},
        )
    }

    /**
     * The icon set, read in before the page is composed. The app loads it off
     * the main thread and draws its first frame without it; a picture of that
     * frame would have no checkboxes, no chevrons and no callout icons in it.
     */
    private val lucide by lazy {
        runBlocking { LucideSet.load(ApplicationProvider.getApplicationContext()) }
    }

    /** A phone-wide page in the app's own theme, sized to what is on it. */
    private fun page(
        theme: ThemeChoice = ThemeChoice.DARK,
        reading: ReadingSettings = ReadingSettings(),
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            NotesTheme(theme) {
                CompositionLocalProvider(LocalReading provides reading, LocalLucide provides lucide) {
                    Surface(
                        Modifier
                            .testTag(PAGE)
                            .width(PAGE_WIDTH)
                            .wrapContentHeight(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        content()
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onNodeWithTag(PAGE).captureRoboImage(
            // The directory is the build's to choose (`roborazzi.outputDir`),
            // the name is the test's.
            filePath = File(roborazziSystemPropertyOutputDirectory(), "$name.png").path,
            roborazziOptions =
                RoborazziOptions(
                    compareOptions =
                        RoborazziOptions.CompareOptions(
                            // No share of the pixels may change -- a chevron
                            // that grew by 8dp is a few hundred pixels of a
                            // quarter of a million, and a percentage threshold
                            // let exactly that through. What is allowed is a
                            // small change in a pixel's colour, which is what
                            // another OS's anti-aliasing produces.
                            changeThreshold = 0f,
                            imageComparator = SimpleImageComparator(maxDistance = MAX_PIXEL_DISTANCE),
                        ),
                ),
        )
    }

    private companion object {
        const val PAGE = "page"
        val PAGE_WIDTH = 411.dp

        /**
         * How far a pixel's colour may drift (0 to 1, across all channels)
         * before it counts as changed. Roborazzi's own default is 0.007;
         * this is wider, for the edge of a glyph drawn on a different OS, and
         * still far below the distance between any mark and its background.
         */
        const val MAX_PIXEL_DISTANCE = 0.1f
    }
}
