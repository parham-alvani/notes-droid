package me.parham1995.notes.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** The values are those of `:root[data-palette="soft"]` in the desktops' reader page. */
class SoftPaletteTest {
    @Test
    fun softensNazAsTheReaderDoes() {
        assertEquals(0xE9B87D, SoftPalette.soften(0xFFB040)) // accent
        assertEquals(0xE3E499, SoftPalette.soften(0xFFFF88)) // heading 2
        assertEquals(0x7DD5F4, SoftPalette.soften(0x60D8FF)) // heading 3
        assertEquals(0xA3ECBF, SoftPalette.soften(0x20FFA0)) // green
        assertEquals(0xE1717E, SoftPalette.soften(0xFF5070)) // red keeps more strength
    }

    @Test
    fun neutralsAreATable() {
        assertEquals(0x2F2F2D, SoftPalette.soften(0x323232))
        assertEquals(0xE2E2DD, SoftPalette.soften(0xF5F5F0))
    }

    @Test
    fun aGreyStaysAsItIs() {
        assertEquals(0xA8A8A0, SoftPalette.soften(0xA8A8A0))
    }
}
