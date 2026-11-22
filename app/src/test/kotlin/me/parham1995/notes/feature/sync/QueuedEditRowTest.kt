package me.parham1995.notes.feature.sync

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.EditKind
import me.parham1995.notes.data.database.PendingEditEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A save from the editor that waits in the queue holds a note's whole text.
 * When it can never land, that text is the only copy of what was typed, so
 * its row gives it back before it is discarded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class QueuedEditRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun edit(kind: EditKind) =
        PendingEditEntity(
            vaultId = 1,
            path = "Plan.md",
            kind = kind.name,
            anchor = "# Plan\n",
            text = "# Plan\nwhat was typed\n",
            summary = "docs: edit Plan",
            createdAt = 0,
            lastError = "the note changed while it was open here - nothing was written",
        )

    @Test
    fun `an editor's save that waits gives its text back, and discard reaches its handler`() {
        var discarded = 0
        compose.setContent { QueuedEditRow(edit(EditKind.EDIT), onDiscard = { discarded++ }) }

        compose.onNodeWithText("Copy My Text").performClick()
        compose.waitForIdle()
        val clipboard =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        ).isEqualTo("# Plan\nwhat was typed\n")
        assertThat(discarded).isEqualTo(0)

        compose.onNodeWithText("Discard").performClick()
        assertThat(discarded).isEqualTo(1)
    }

    @Test
    fun `an edit of another kind has no text to copy`() {
        compose.setContent { QueuedEditRow(edit(EditKind.REPLACE_LINE), onDiscard = {}) }
        compose.onNodeWithText("Copy My Text").assertDoesNotExist()
        compose.onNodeWithText("Discard").assertExists()
    }
}
