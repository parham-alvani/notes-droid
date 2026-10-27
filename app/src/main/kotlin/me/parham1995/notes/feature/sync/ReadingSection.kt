package me.parham1995.notes.feature.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.BrowserSort
import me.parham1995.notes.data.LineWidth
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.data.StartScreen
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.ui.inScript

private const val PERCENT = 100

/**
 * How notes are set.
 *
 * The first settings in this app that are about reading rather than syncing,
 * which for a reader was an odd gap: nine settings, all of them about how the
 * vault arrives and none about how it looks once it has.
 */
@Composable
internal fun ReadingCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val reading = state.settings.reading

    SectionCard(stringResource(R.string.card_text)) {
        Text(stringResource(R.string.settings_size), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingSettings.TEXT_SCALES.forEach { scale ->
                FilterChip(
                    selected = reading.textScale == scale,
                    onClick = { viewModel.setTextScale(scale) },
                    label = { Text(stringResource(R.string.settings_percent, (scale * PERCENT).toInt())) },
                )
            }
        }

        Text(stringResource(R.string.settings_line_spacing), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingSettings.LINE_SPACINGS.forEach { spacing ->
                FilterChip(
                    selected = reading.lineSpacing == spacing,
                    onClick = { viewModel.setLineSpacing(spacing) },
                    label = {
                        Text(
                            if (spacing == 1f) {
                                stringResource(R.string.spacing_normal)
                            } else {
                                stringResource(R.string.settings_percent, (spacing * PERCENT).toInt())
                            },
                        )
                    },
                )
            }
        }

        Text(stringResource(R.string.settings_line_width), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineWidth.entries.forEach { width ->
                FilterChip(
                    selected = reading.lineWidth == width,
                    onClick = { viewModel.setLineWidth(width) },
                    label = { Text(stringResource(width.label)) },
                )
            }
        }
        Text(
            stringResource(R.string.help_line_width),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Shown at the size and spacing chosen, so the choice is visible where
        // it is made rather than two screens away.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "The quick brown fox. " + (if (reading.persianFont) "روباه قهوه‌ای چابک." else "روباه قهوه‌ای چابک."),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge.inScript(),
            )
        }
    }

    SectionCard(stringResource(R.string.card_appearance)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice.entries.forEach { choice ->
                FilterChip(
                    selected = reading.theme == choice,
                    onClick = { viewModel.setTheme(choice) },
                    label = { Text(stringResource(choice.label)) },
                )
            }
        }
        Text(
            stringResource(R.string.help_theme),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.settings_widget_background), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingSettings.WIDGET_OPACITIES.forEach { percent ->
                FilterChip(
                    selected = reading.widgetOpacity == percent,
                    onClick = { viewModel.setWidgetOpacity(percent) },
                    label = {
                        Text(
                            when (percent) {
                                100 -> stringResource(R.string.widget_background_solid)
                                0 -> stringResource(R.string.widget_background_clear)
                                else -> stringResource(R.string.settings_percent, percent)
                            },
                        )
                    },
                )
            }
        }
        Text(
            stringResource(R.string.help_widget_background),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SwitchRow(
            title = stringResource(R.string.settings_persian_font),
            help = stringResource(R.string.help_persian_font),
            checked = reading.persianFont,
            onCheckedChange = viewModel::setPersianFont,
        )

        SwitchRow(
            title = stringResource(R.string.settings_stylus),
            help = stringResource(R.string.settings_stylus_note),
            checked = reading.stylusSpotlight,
            onCheckedChange = viewModel::setStylusSpotlight,
        )

        SwitchRow(
            title = stringResource(R.string.settings_hide_done),
            help = stringResource(R.string.help_hide_done),
            checked = reading.hideCompletedTasks,
            onCheckedChange = viewModel::setHideCompletedTasks,
        )

        SwitchRow(
            title = stringResource(R.string.settings_hold_to_define),
            help = stringResource(R.string.help_hold_to_define),
            checked = reading.holdToDefine,
            onCheckedChange = viewModel::setHoldToDefine,
        )
    }

    SectionCard(stringResource(R.string.card_behaviour)) {
        Text(stringResource(R.string.settings_open_on), style = MaterialTheme.typography.labelMedium)
        // Wrapped: four choices are wider than a phone's card.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StartScreen.entries.forEach { screen ->
                FilterChip(
                    selected = reading.startScreen == screen,
                    onClick = { viewModel.setStartScreen(screen) },
                    label = { Text(stringResource(screen.label)) },
                )
            }
        }

        Text(stringResource(R.string.settings_folder_order), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserSort.entries.forEach { sort ->
                FilterChip(
                    selected = reading.browserSort == sort,
                    onClick = { viewModel.setBrowserSort(sort) },
                    label = { Text(stringResource(sort.label)) },
                )
            }
        }

        // Nothing is hidden while hidden files are shown, so this would
        // be a switch that does nothing.
        SwitchRow(
            title = stringResource(R.string.settings_hide_agent_files),
            help = stringResource(R.string.help_hide_agent_files),
            checked = reading.hideAgentFiles,
            onCheckedChange = viewModel::setHideAgentFiles,
            enabled = !reading.showHiddenFiles,
        )

        SwitchRow(
            title = stringResource(R.string.settings_show_hidden_files),
            help = stringResource(R.string.help_show_hidden_files),
            checked = reading.showHiddenFiles,
            onCheckedChange = viewModel::setShowHiddenFiles,
        )

        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            help = stringResource(R.string.help_keep_screen_on),
            checked = reading.keepScreenOn,
            onCheckedChange = viewModel::setKeepScreenOn,
        )

        SwitchRow(
            title = stringResource(R.string.settings_tap_full_screen),
            help = stringResource(R.string.help_tap_full_screen),
            checked = reading.tapForFullScreen,
            onCheckedChange = viewModel::setTapForFullScreen,
        )
    }
}

/**
 * What each choice is called on screen. Named here rather than from the enum
 * constant, which showed "recently_opened" lower-cased in whatever language
 * the phone was set to.
 */
@get:StringRes
private val ThemeChoice.label: Int
    get() =
        when (this) {
            ThemeChoice.DARK -> R.string.theme_dark
            ThemeChoice.LIGHT -> R.string.theme_light
            ThemeChoice.SYSTEM -> R.string.theme_system
        }

@get:StringRes
private val LineWidth.label: Int
    get() =
        when (this) {
            LineWidth.NARROW -> R.string.width_narrow
            LineWidth.COMFORTABLE -> R.string.width_comfortable
            LineWidth.FULL -> R.string.width_full
        }

@get:StringRes
private val StartScreen.label: Int
    get() =
        when (this) {
            StartScreen.HOME -> R.string.nav_home
            StartScreen.BROWSE -> R.string.nav_browse
            StartScreen.TASKS -> R.string.tasks_title
            StartScreen.SEARCH -> R.string.nav_search
        }

@get:StringRes
private val BrowserSort.label: Int
    get() =
        when (this) {
            BrowserSort.NAME -> R.string.sort_name
            BrowserSort.RECENTLY_OPENED -> R.string.sort_recently_opened
            BrowserSort.RECENTLY_CHANGED -> R.string.sort_recently_changed
        }
