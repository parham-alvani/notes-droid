package me.parham1995.notes.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.parham1995.notes.obsidian.Beautitab
import me.parham1995.notes.ui.icon.LocalLucide
import me.parham1995.notes.ui.icon.LucideSet
import me.parham1995.notes.ui.theme.NotesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The home page does what it shows: the search hands its words on, a recent
 * note and a bookmark open, and what the vault's Beautitab settings turn off
 * is not there.
 */
@RunWith(RobolectricTestRunner::class)
class HomePageTest {
    @get:Rule
    val compose = createComposeRule()

    private val lucide by lazy {
        runBlocking { LucideSet.load(ApplicationProvider.getApplicationContext()) }
    }

    private val opened = mutableListOf<Pair<Long, String?>>()
    private val searched = mutableListOf<String>()

    private fun show(
        state: HomeUiState = HomeFixture.state,
        picture: Boolean = false,
    ) {
        val bitmap = if (picture) HomeFixture.picture() else null
        compose.setContent {
            NotesTheme {
                CompositionLocalProvider(LocalLucide provides lucide) {
                    HomePage(
                        state = state,
                        picture = bitmap,
                        now = HomeFixture.now,
                        onOpenNote = { id, heading -> opened += id to heading },
                        onSearch = { searched += it },
                    )
                }
            }
        }
    }

    @Test
    fun `the time, the greeting, the lists and the quote are shown`() {
        show()
        compose.onNodeWithText("09:41").assertExists()
        compose.onNodeWithText("Hello, Ada.").assertExists()
        compose.onNodeWithText("Tomatoes").assertExists()
        compose.onNodeWithText("Weekly review").assertExists()
        compose.onNodeWithText("“Read slowly, and read again.”").assertExists()
        compose.onNodeWithTag(PICTURE_TAG).assertDoesNotExist()
    }

    @Test
    fun `the search hands what was typed to search`() {
        show()
        compose.onNodeWithTag(SEARCH_TAG).performTextInput("bread")
        compose.onNodeWithTag(SEARCH_TAG).performImeAction()
        assertThat(searched).containsExactly("bread")
    }

    @Test
    fun `a recent note and a bookmark open`() {
        show()
        compose.onNodeWithText("Bread").performScrollTo().performClick()
        compose.onNodeWithText("Weekly review").performScrollTo().performClick()
        assertThat(opened).containsExactly(2L to null, 4L to "Next week").inOrder()
    }

    @Test
    fun `what the settings turn off is not there`() {
        show(
            HomeUiState(
                settings = Beautitab(showTime = false, showGreeting = false, showSearch = false),
                recent = emptyList(),
            ),
        )
        compose.onNodeWithText("09:41").assertDoesNotExist()
        compose.onNodeWithText("Good morning").assertDoesNotExist()
        compose.onNodeWithTag(SEARCH_TAG).assertDoesNotExist()
        compose.onNodeWithText("Recent").assertDoesNotExist()
    }

    @Test
    fun `with no greeting of its own it greets by the time of day`() {
        show(HomeUiState())
        compose.onNodeWithText("Good morning").assertExists()
    }

    @Test
    fun `the picture is drawn when there is one`() {
        show(picture = true)
        compose.onNodeWithTag(PICTURE_TAG).assertExists()
    }

    @Test
    fun `the setting's buttons reach their handlers, and clear only when there is a picture`() {
        var chose = 0
        var cleared = 0
        compose.setContent {
            NotesTheme {
                HomePictureSettings(chosen = false, picture = null, onChoose = { chose++ }, onClear = { cleared++ })
            }
        }
        compose.onNodeWithText("Choose picture").performClick()
        compose.onNodeWithText("Clear").assertIsNotEnabled()
        assertThat(chose).isEqualTo(1)
        assertThat(cleared).isEqualTo(0)
    }

    @Test
    fun `clear is offered for a picture of the app's own`() {
        var cleared = 0
        compose.setContent {
            NotesTheme {
                HomePictureSettings(
                    chosen = true,
                    picture = HomeFixture.picture(),
                    onChoose = {},
                    onClear = { cleared++ },
                )
            }
        }
        compose.onNodeWithText("Clear").assertIsEnabled().performClick()
        compose.onNodeWithTag(PICTURE_TAG).assertExists()
        assertThat(cleared).isEqualTo(1)
    }
}
