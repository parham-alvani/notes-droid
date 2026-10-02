package me.parham1995.notes.widget

import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders
import kotlinx.coroutines.flow.first
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
internal fun widgetColors(theme: ThemeChoice): ColorProviders =
    when (theme) {
        ThemeChoice.DARK -> ColorProviders(NazScheme)
        ThemeChoice.LIGHT -> ColorProviders(DaylightScheme)
        // Day and night each have a scheme, and the host switches between
        // them as the system does.
        ThemeChoice.SYSTEM -> ColorProviders(light = DaylightScheme, dark = NazScheme)
    }

/** The setting as it stands, or dark -- the app's own default -- when it cannot be read. */
internal suspend fun WidgetEntryPoint.widgetColors(): ColorProviders =
    widgetColors(
        runCatchingUnlessCancelled {
            settingsStore()
                .settings
                .first()
                .reading.theme
        }.getOrDefault(ThemeChoice.DARK),
    )
