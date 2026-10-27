package me.parham1995.notes.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.ThemeChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A see-through widget lets the wallpaper through its background and
 * nowhere else: what is read on it keeps its colour.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetThemeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `solid is the default and draws the whole background`() {
        val colors = widgetColors(ThemeChoice.DARK)
        assertThat(colors.background.getColor(context).alpha).isEqualTo(1f)
    }

    @Test
    fun `the background takes the chosen opacity and the text does not`() {
        for (theme in ThemeChoice.entries) {
            val half = widgetColors(theme, opacity = 50)
            assertThat(half.background.getColor(context).alpha).isWithin(0.01f).of(0.5f)
            assertThat(half.onBackground.getColor(context).alpha).isEqualTo(1f)
            assertThat(half.primaryContainer.getColor(context).alpha).isEqualTo(1f)
        }
    }

    @Test
    fun `clear leaves no background at all`() {
        assertThat(widgetColors(ThemeChoice.LIGHT, opacity = 0).background.getColor(context).alpha).isEqualTo(0f)
    }
}
