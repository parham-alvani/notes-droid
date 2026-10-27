package me.parham1995.notes.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.runCatchingUnlessCancelled
import me.parham1995.notes.ui.theme.DaylightScheme
import me.parham1995.notes.ui.theme.NazScheme

/**
 * The widgets wear the vault's colours, as the app does.
 *
 * Not Glance's Material defaults and not the launcher's dynamic colours: the
 * same two schemes `NotesTheme` draws with, so a widget sitting under the app
 * is recognisably the same thing. The theme setting is read too -- a phone
 * set to dark whose owner chose the light reader gets light widgets, since
 * the choice was about reading and a widget is read.
 */
internal fun widgetColors(
    theme: ThemeChoice,
    opacity: Int = 100,
): ColorProviders {
    val dark = NazScheme.seeThrough(opacity)
    val light = DaylightScheme.seeThrough(opacity)
    return when (theme) {
        ThemeChoice.DARK -> ColorProviders(dark)
        ThemeChoice.LIGHT -> ColorProviders(light)
        // Day and night each have a scheme, and the host switches between
        // them as the system does.
        ThemeChoice.SYSTEM -> ColorProviders(light = light, dark = dark)
    }
}

/**
 * The scheme with its background let through to the wallpaper by
 * [opacity] percent. Only the background: the text and the pinned notes'
 * cards keep their colours, so what is read stays as it was.
 */
private fun ColorScheme.seeThrough(opacity: Int): ColorScheme =
    if (opacity >= 100) this else copy(background = background.copy(alpha = opacity.coerceIn(0, 100) / 100f))

/**
 * The colours, kept current while the widget's session lives.
 *
 * A widget's session reads [widgetColors] once, when it starts, and an
 * update to a session that is still running only recomposes it -- so a theme
 * or a background chosen a moment after the widget was drawn did not show
 * until the session ended. [initial] is that first read, so the first frame
 * is drawn in the right colours and does not wait for the settings to load.
 */
@Composable
internal fun WidgetEntryPoint.liveColors(initial: ColorProviders): ColorProviders {
    val live =
        remember {
            settingsStore()
                .settings
                .map { it.reading.theme to it.reading.widgetOpacity }
                .distinctUntilChanged()
                .map { (theme, opacity) -> widgetColors(theme, opacity) }
        }
    return live.collectAsState(initial).value
}

/** The settings as they stand, or the app's defaults when they cannot be read. */
internal suspend fun WidgetEntryPoint.widgetColors(): ColorProviders {
    val reading =
        runCatchingUnlessCancelled {
            settingsStore()
                .settings
                .first()
                .reading
        }.getOrNull()
    return widgetColors(reading?.theme ?: ThemeChoice.DARK, reading?.widgetOpacity ?: 100)
}
