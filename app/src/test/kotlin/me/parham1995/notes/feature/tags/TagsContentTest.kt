package me.parham1995.notes.feature.tags

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.markdown.TagTree
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TagsContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a nested tag is chosen by its whole path`() {
        val chosen = mutableListOf<String>()
        val rows = TagTree.flatten(TagTree.build(listOf("project/alpha" to 1L, "idea" to 2L)))
        compose.setContent {
            TagsContent(rows = rows, selected = null, onBack = {}, onSelect = { chosen += it }, onOpenNote = {})
        }

        // Shown by its last part, asked for by all of it.
        compose.onNodeWithText("alpha").performClick()
        compose.onNodeWithText("project").performClick()
        assertThat(chosen).containsExactly("project/alpha", "project").inOrder()
    }

    @Test
    fun `a tag's notes open when tapped`() {
        val opened = mutableListOf<Long>()
        val note =
            NoteEntity(
                id = 42,
                vaultId = 1,
                path = "Folder/Tagged.md",
                parent = "Folder",
                name = "Tagged",
                slug = "tagged",
                title = "Tagged",
                blobSha = "s",
                size = 1,
                isFolderNote = false,
                isRtl = false,
                hasMermaid = false,
                hasMath = false,
                indexedAt = 0,
            )
        compose.setContent {
            TagsContent(
                rows = emptyList(),
                selected = TaggedNotes("idea", listOf(note)),
                onBack = {},
                onSelect = {},
                onOpenNote = { opened += it },
            )
        }

        compose.onNodeWithText("#idea").assertExists()
        compose.onNodeWithText("Tagged").performClick()
        assertThat(opened).containsExactly(42L)
    }
}
