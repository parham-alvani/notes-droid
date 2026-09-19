package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How the set of open notes behaves, which is most of what tabs are. */
class NoteTabsTest {
    private fun tabs() = NoteTabs()

    private fun ids(t: NoteTabs) =
        t.state.value.tabs
            .map { it.noteId }

    @Test
    fun `the first note opened becomes the only tab`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        assertThat(ids(t)).containsExactly(1L)
        assertThat(t.state.value.active).isEqualTo(0)
    }

    @Test
    fun `opening in place replaces the tab being read`() {
        val t = tabs()
        t.open(1, inNewTab = false)
        t.open(2, inNewTab = false)
        assertThat(ids(t)).containsExactly(2L)
    }

    @Test
    fun `a new tab lands beside the one it came from`() {
        // A browser does this, and the reason is the same: a link opened from
        // the second tab belongs next to it, not after everything else.
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = true)
        t.select(0)
        t.open(3, inNewTab = true)
        assertThat(ids(t)).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(t.state.value.active).isEqualTo(1)
    }

    @Test
    fun `a note that is already open is switched to, not opened twice`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = true)
        t.open(1, inNewTab = true)
        assertThat(ids(t)).containsExactly(1L, 2L).inOrder()
        assertThat(t.state.value.active).isEqualTo(0)
    }

    @Test
    fun `closing a tab to the left keeps you on the one you were reading`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = true)
        t.open(3, inNewTab = true)
        assertThat(
            t.state.value.current
                ?.noteId,
        ).isEqualTo(3L)

        t.close(0)

        assertThat(ids(t)).containsExactly(2L, 3L).inOrder()
        assertThat(
            t.state.value.current
                ?.noteId,
        ).isEqualTo(3L)
    }

    @Test
    fun `closing the last tab on the right falls back to its neighbour`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = true)

        assertThat(t.close(1)).isTrue()

        assertThat(
            t.state.value.current
                ?.noteId,
        ).isEqualTo(1L)
    }

    @Test
    fun `closing the only tab says so, so the screen can be left`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        assertThat(t.close(0)).isFalse()
        assertThat(ids(t)).isEmpty()
    }

    @Test
    fun `a title arrives after the tab does`() {
        // The strip is drawn before the note has loaded, so tabs start
        // untitled and are filled in.
        val t = tabs()
        t.open(7, inNewTab = true)
        assertThat(
            t.state.value.current
                ?.title,
        ).isEmpty()

        t.retitle(7, "Kafka")

        assertThat(
            t.state.value.current
                ?.title,
        ).isEqualTo("Kafka")
    }
}
