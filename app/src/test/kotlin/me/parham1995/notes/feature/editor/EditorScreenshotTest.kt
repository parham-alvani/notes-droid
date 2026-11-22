package me.parham1995.notes.feature.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import java.time.LocalDate

/**
 * The editor as it is drawn, in each palette, compared with what it drew last
 * time: the sizes, the tints, the callout's panel and icon, the quote's bar,
 * the caret's line, and which marks show. A made-up note, on the same setup
 * as `RendererScreenshotTest`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val lucide by lazy {
        runBlocking { LucideSet.load(ApplicationProvider.getApplicationContext()) }
    }

    private val note =
        """
        # Weekly review

        Plain prose with **bold**, *italic*, `code`, a [[Garden plan|plan]] and a #tag.

        ## Marked

        ==plain==, ==🔴 red==, ==🟢green== and ==🔵blue== with **bold** in ==a **mark**==.

        - one item
            * a second level
                1. a third level

        > A quotation, with a second line
        > under it.

        > [!warning] Mind the gap
        > A body with a [link](https://example.com).

        > [!tip]
        > A callout with no title reads its kind.

        ### یادداشت

        این یک خط فارسی با **تاکید** است.
        """.trimIndent()

    /** Tasks in every status with their boxes, and their dates as chips: one late, one on a closed task, one raw. */
    private val tasks =
        """
        ## Tasks

        - [ ] Pay the rent 📅 2026-11-20 ⏳ 2026-11-18 🔁 every month ➕ 2026-11-01
        - [/] Write the review ⏳ 2026-11-22
        - [x] Call the bank ➕ 2026-11-10 ✅ 2026-11-21
        - [-] Sell the bike [reason:: no buyer] ❌ 2026-11-19
        - [>] Forwarded to next week 🛫 2026-11-24
        - [?] A question
            1. [ ] A numbered task 📅 2026-12-01
        - [ ] The caret is in this date 📅 2026-11-30

        > [!todo] In a callout
        > - [ ] A task here 📅 2026-11-25

        - [x] کار انجام شده ✅ 2026-11-21
        - [ ] کار فارسی 📅 2026-11-23
        """.trimIndent()

    @Test
    fun `tasks in the editor, dark`() =
        editor(
            "editor_tasks_dark",
            text = tasks,
            caret =
                tasks.indexOf("2026-11-30") + 4,
        )

    @Test
    fun `tasks in the editor, light`() =
        editor("editor_tasks_light", text = tasks, caret = tasks.indexOf("2026-11-30") + 4, theme = ThemeChoice.LIGHT)

    /** The caret in "bold": its two marks show, every other mark is hidden, and its line is tinted. */
    @Test
    fun `the editor, dark, with the caret in a bold word`() = editor("editor_dark", caret = note.indexOf("bold") + 2)

    @Test
    fun `the editor, soft dark`() = editor("editor_soft", reading = ReadingSettings(softDark = true))

    @Test
    fun `the editor, light`() = editor("editor_light", theme = ThemeChoice.LIGHT)

    /** With live preview off every mark shows, in the colours of the look. */
    @Test
    fun `the editor with every mark shown`() = editor("editor_raw", livePreview = false)

    private fun editor(
        name: String,
        theme: ThemeChoice = ThemeChoice.DARK,
        reading: ReadingSettings = ReadingSettings(),
        // On the heading's line by default, so one line shows its marks.
        caret: Int = 0,
        livePreview: Boolean = true,
        text: String = note,
    ) {
        compose.setContent {
            NotesTheme(theme, softDark = reading.softDark) {
                CompositionLocalProvider(LocalReading provides reading, LocalLucide provides lucide) {
                    Surface(
                        Modifier.testTag(PAGE).width(411.dp).wrapContentHeight(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        NoteEditor(
                            value = TextFieldValue(text, TextRange(caret)),
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            livePreview = livePreview,
                            // A day of its own, so a late date is late on any day the test runs.
                            today = LocalDate.of(2026, 11, 22),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(PAGE).captureRoboImage(
            filePath = File(roborazziSystemPropertyOutputDirectory(), "$name.png").path,
            roborazziOptions =
                RoborazziOptions(
                    compareOptions =
                        RoborazziOptions.CompareOptions(
                            changeThreshold = 0f,
                            imageComparator = SimpleImageComparator(maxDistance = MAX_PIXEL_DISTANCE),
                        ),
                ),
        )
    }

    private companion object {
        const val PAGE = "editor-page"

        /** The renderer's tolerance, for the same reason: another OS's anti-aliasing. */
        const val MAX_PIXEL_DISTANCE = 0.1f
    }
}
