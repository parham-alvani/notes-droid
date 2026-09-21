package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How the set of open notes behaves, which is most of what tabs are. */
class NoteTabsTest {
    private fun tabs() = Tabs()

    private fun ids(t: Tabs) = t.state.tabs.map { it.noteId }

    /**
     * The transitions, without the storage around them.
     *
     * What a tab does is arithmetic on a list; what the singleton adds is
     * writing it down and reading it back. Testing the arithmetic needs
     * neither a database nor a settings file.
     */
    private class Tabs {
        var state = TabsState()

        fun open(
            noteId: Long,
            inNewTab: Boolean,
            fresh: Boolean = false,
        ) {
            state = state.opening(noteId, inNewTab, fresh)
        }

        fun back(): Boolean {
            val next = state.goingBack()
            val moved = next != state
            state = next
            return moved
        }

        fun select(index: Int) {
            state = state.selecting(index)
        }

        fun close(index: Int): Boolean {
            state = state.closing(index)
            return state.tabs.isNotEmpty()
        }

        fun retitle(
            noteId: Long,
            title: String,
        ) {
            state = state.retitling(noteId, title)
        }
    }

    @Test
    fun `the first note opened becomes the only tab`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        assertThat(ids(t)).containsExactly(1L)
        assertThat(t.state.active).isEqualTo(0)
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
        assertThat(t.state.active).isEqualTo(1)
    }

    @Test
    fun `a note that is already open is switched to, not opened twice`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = true)
        t.open(1, inNewTab = true)
        assertThat(ids(t)).containsExactly(1L, 2L).inOrder()
        assertThat(t.state.active).isEqualTo(0)
    }

    @Test
    fun `closing a tab to the left keeps you on the one you were reading`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = true)
        t.open(3, inNewTab = true)
        assertThat(
            t.state.current
                ?.noteId,
        ).isEqualTo(3L)

        t.close(0)

        assertThat(ids(t)).containsExactly(2L, 3L).inOrder()
        assertThat(
            t.state.current
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
            t.state.current
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
    fun `a fresh tab has nowhere to go back to`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        assertThat(
            t.state.current
                ?.canGoBack,
        ).isFalse()
        assertThat(t.back()).isFalse()
    }

    @Test
    fun `back retraces the tab you are in, not the app`() {
        // Two links followed in one tab, one in another. Back in the second
        // must not walk into the first.
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = false)
        t.open(3, inNewTab = true)
        t.open(4, inNewTab = false)

        assertThat(t.back()).isTrue()
        assertThat(
            t.state.current
                ?.noteId,
        ).isEqualTo(3L)
        assertThat(
            t.state.current
                ?.canGoBack,
        ).isFalse()

        // The other tab kept its own trail.
        t.select(0)
        assertThat(
            t.state.current
                ?.noteId,
        ).isEqualTo(2L)
        assertThat(t.back()).isTrue()
        assertThat(
            t.state.current
                ?.noteId,
        ).isEqualTo(1L)
    }

    @Test
    fun `opening in place adds to the trail rather than erasing it`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = false)
        assertThat(ids(t)).containsExactly(2L)
        assertThat(
            t.state.current
                ?.history,
        ).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `going back and then somewhere new drops what was ahead`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = false)
        t.back()
        t.open(3, inNewTab = false)

        assertThat(
            t.state.current
                ?.history,
        ).containsExactly(1L, 3L).inOrder()
        assertThat(
            t.state.current
                ?.noteId,
        ).isEqualTo(3L)
    }

    @Test
    fun `a title arrives after the tab does`() {
        // The strip is drawn before the note has loaded, so tabs start
        // untitled and are filled in.
        val t = tabs()
        t.open(7, inNewTab = true)
        assertThat(
            t.state.current
                ?.title,
        ).isEmpty()

        t.retitle(7, "Kafka")

        assertThat(
            t.state.current
                ?.title,
        ).isEqualTo("Kafka")
    }

    /**
     * The bug this exists to stop coming back: opening a note from the browser
     * pushed it onto whatever trail the tab already held, so back went to a
     * note the reader had not navigated from and the browser was no longer
     * behind it at all.
     */
    @Test
    fun `arriving from outside the reader begins the trail again`() {
        val t = tabs()
        t.open(1, inNewTab = false)
        t.open(2, inNewTab = false)
        assertThat(t.state.current!!.history).containsExactly(1L, 2L).inOrder()

        t.open(3, inNewTab = false, fresh = true)

        assertThat(t.state.current!!.history).containsExactly(3L)
        assertThat(t.state.current!!.canGoBack).isFalse()
    }

    @Test
    fun `a fresh arrival leaves the other tabs alone`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = true)
        t.open(3, inNewTab = false)
        assertThat(t.state.tabs).hasSize(2)

        t.open(4, inNewTab = false, fresh = true)

        assertThat(t.state.tabs).hasSize(2)
        assertThat(t.state.tabs[0].history).containsExactly(1L)
        assertThat(t.state.current!!.history).containsExactly(4L)
    }

    @Test
    fun `a fresh arrival at a note already open switches to it`() {
        val t = tabs()
        t.open(1, inNewTab = true)
        t.open(2, inNewTab = true)
        t.select(0)

        t.open(2, inNewTab = false, fresh = true)

        assertThat(t.state.active).isEqualTo(1)
        assertThat(ids(t)).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `following a link still pushes onto the trail`() {
        val t = tabs()
        t.open(1, inNewTab = false, fresh = true)
        t.open(2, inNewTab = false)

        assertThat(t.state.current!!.history).containsExactly(1L, 2L).inOrder()
        assertThat(t.state.current!!.canGoBack).isTrue()
    }
}
