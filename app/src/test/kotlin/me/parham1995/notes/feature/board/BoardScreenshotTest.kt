package me.parham1995.notes.feature.board

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import kotlinx.coroutines.runBlocking
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.markdown.TaskBoard
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
 * The board as it is drawn, compared with what it drew last time: the column
 * colours, a card's stripe, chips and marks, and a Persian card set right to
 * left. Made-up tasks, on the same setup as `RendererScreenshotTest`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val lucide by lazy {
        runBlocking { LucideSet.load(ApplicationProvider.getApplicationContext()) }
    }

    @Test
    fun `the status board, dark`() = board(ThemeChoice.DARK, TaskBoard.Grouping.STATUS, "board_dark")

    @Test
    fun `the status board, light`() = board(ThemeChoice.LIGHT, TaskBoard.Grouping.STATUS, "board_light")

    @Test
    fun `the day board, dark`() = board(ThemeChoice.DARK, TaskBoard.Grouping.DAY, "board_day_dark")

    private fun board(
        theme: ThemeChoice,
        by: TaskBoard.Grouping,
        name: String,
    ) {
        val columns = TaskBoard.columns(BoardFixture.rows, by, BoardFixture.today) { it.facts() }
        compose.setContent {
            NotesTheme(theme) {
                CompositionLocalProvider(LocalLucide provides lucide) {
                    Surface(
                        Modifier.testTag(PAGE).width(411.dp).height(560.dp),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Column {
                            BoardControls(grouping = by, allClosed = false, onGroup = {}, onAllClosed = {})
                            BoardColumns(
                                columns = columns,
                                canWrite = true,
                                today = BoardFixture.today,
                                onMove = { _, _ -> },
                                onToggle = {},
                                onOpen = {},
                            )
                        }
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
        const val PAGE = "board"

        /** The renderer's tolerance, for the same reason: another OS's anti-aliasing. */
        const val MAX_PIXEL_DISTANCE = 0.1f
    }
}
