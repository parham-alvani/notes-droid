package me.parham1995.notes.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.ui.theme.NotesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A sheet's window asks for bars that can be read against the sheet.
 *
 * Left to Material, a naz sheet asked for light bars -- its container is
 * also `surfaceVariant`, whose content colour is a grey that counts as dark
 * -- and the phone drew a pale band of dark buttons under every dark sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class SheetPropertiesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a dark sheet asks for light icons on its bars`() {
        assertThat(propertiesIn(ThemeChoice.DARK)).isEqualTo(bars(light = false))
    }

    @Test
    fun `a light sheet asks for dark icons on its bars`() {
        assertThat(propertiesIn(ThemeChoice.LIGHT)).isEqualTo(bars(light = true))
    }

    private fun propertiesIn(theme: ThemeChoice): ModalBottomSheetProperties? {
        var properties: ModalBottomSheetProperties? = null
        compose.setContent { NotesTheme(theme) { properties = sheetProperties() } }
        compose.waitForIdle()
        return properties
    }

    private fun bars(light: Boolean) =
        ModalBottomSheetProperties(isAppearanceLightStatusBars = light, isAppearanceLightNavigationBars = light)
}
