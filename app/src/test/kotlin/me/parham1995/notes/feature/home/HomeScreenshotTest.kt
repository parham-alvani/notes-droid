package me.parham1995.notes.feature.home

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
 * The home page as it is drawn: over a generated picture in both themes, so
 * the scrim is seen to keep the text readable in each, and with no picture.
 * On the same setup as `RendererScreenshotTest`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val lucide by lazy {
        runBlocking { LucideSet.load(ApplicationProvider.getApplicationContext()) }
    }

    @Test
    fun `over a picture, dark`() = home(ThemeChoice.DARK, picture = true, "home_picture_dark")

    @Test
    fun `over a picture, light`() = home(ThemeChoice.LIGHT, picture = true, "home_picture_light")

    @Test
    fun `with no picture`() = home(ThemeChoice.DARK, picture = false, "home_plain_dark")

    private fun home(
        theme: ThemeChoice,
        picture: Boolean,
        name: String,
    ) {
        val bitmap = if (picture) HomeFixture.picture() else null
        compose.setContent {
            NotesTheme(theme) {
                CompositionLocalProvider(LocalLucide provides lucide) {
                    HomePage(
                        state = HomeFixture.state,
                        picture = bitmap,
                        now = HomeFixture.now,
                        onOpenNote = { _, _ -> },
                        onSearch = {},
                        modifier = Modifier.size(411.dp, 891.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_TAG).captureRoboImage(
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
        /** The renderer's tolerance, for the same reason: another OS's anti-aliasing. */
        const val MAX_PIXEL_DISTANCE = 0.1f
    }
}
