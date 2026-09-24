package me.parham1995.notes.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which tab a screen pushed on top of one belongs to.
 *
 * Tags is opened from the browser and pushed over it. The bar highlighted
 * nothing there, and tapping Browse restored the browser's saved stack --
 * with Tags still on top -- so the tap did nothing at all. Found on the device.
 */
class TabInUseTest {
    private val tabs = listOf("browse", "tasks", "search", "settings")

    @Test
    fun `a screen pushed over the start tab belongs to it`() {
        // Browse is the start destination; Tags sits on it, no other tab root.
        assertThat(tabInUse(tabs, start = "browse") { false }).isEqualTo("browse")
    }

    @Test
    fun `a screen pushed over another tab belongs to that tab`() {
        // Settings is on the stack above the start; a section sits on it.
        assertThat(tabInUse(tabs, start = "browse") { it == "settings" }).isEqualTo("settings")
    }

    @Test
    fun `the start tab is not mistaken for the one in use when another is open`() {
        // The start destination is always at the bottom of the stack, so its
        // presence says nothing about which tab is being used.
        assertThat(tabInUse(tabs, start = "tasks") { it == "tasks" || it == "search" }).isEqualTo("search")
    }
}
