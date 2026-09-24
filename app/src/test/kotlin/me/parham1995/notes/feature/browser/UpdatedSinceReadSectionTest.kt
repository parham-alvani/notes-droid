package me.parham1995.notes.feature.browser

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.database.VaultEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Updated since you read", from the state to a tap.
 *
 * The layer this app's features have shipped inert in: a section that renders
 * and opens nothing, or a flow that is built and never reaches the state.
 * Fixtures are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class UpdatedSinceReadSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun note(
        id: Long,
        path: String,
    ): NoteEntity {
        val name = path.substringAfterLast('/').removeSuffix(".md")
        return NoteEntity(
            id = id,
            vaultId = 1L,
            path = path,
            parent = path.substringBeforeLast('/', ""),
            name = name,
            slug = name.lowercase(),
            title = name,
            blobSha = "new",
            size = 1,
            isFolderNote = false,
            isRtl = false,
            hasMermaid = false,
            hasMath = false,
            indexedAt = 0,
            openedAt = 1,
            readSha = "old",
        )
    }

    @Test
    fun `each changed note is listed with its folder, and a tap opens it`() {
        val opened = mutableListOf<Long>()
        val rows =
            listOf(
                RecentRow(note(21L, "Journal/2026/Tuesday.md"), updated = true),
                RecentRow(note(22L, "Projects/Garden plan.md"), updated = true),
            )
        compose.setContent {
            LazyColumn { updatedSinceRead(rows, onOpen = { opened += it }, onOpenInNewTab = {}) }
        }

        compose.onNodeWithText("Updated since you read").assertExists()
        compose.onNodeWithText("Journal/2026").assertExists()
        compose.onNodeWithText("Garden plan").performClick()

        assertThat(opened).containsExactly(22L)
    }

    @Test
    fun `nothing is drawn when nothing changed`() {
        compose.setContent {
            LazyColumn { updatedSinceRead(emptyList(), onOpen = {}, onOpenInNewTab = {}) }
        }

        compose.onNodeWithText("Updated since you read").assertDoesNotExist()
    }

    @Test
    fun `what changed reaches the browser's state`() =
        runTest(UnconfinedTestDispatcher()) {
            val updated = MutableStateFlow(emptyList<RecentRow>())
            var shown: BrowserUiState? = null
            val job =
                browserStates(
                    path = flowOf(""),
                    rows = flowOf(emptyList()),
                    recent = flowOf(emptyList()),
                    updated = updated,
                    noteCount = flowOf(3),
                    vaults = flowOf(emptyList<VaultEntity>() to 1L),
                    crashed = flowOf(false),
                ).onEach { shown = it }.launchIn(this)

            assertThat(shown?.updated).isEmpty()

            // A sync lands a change to a note read yesterday.
            updated.value = listOf(RecentRow(note(21L, "Journal/Today.md"), updated = true))
            assertThat(shown?.updated?.map { it.note.id }).containsExactly(21L)

            // It is opened, and so read.
            updated.value = emptyList()
            assertThat(shown?.updated).isEmpty()
            job.cancel()
        }
}
