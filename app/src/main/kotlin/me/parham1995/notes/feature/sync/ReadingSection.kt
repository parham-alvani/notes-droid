package me.parham1995.notes.feature.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.BrowserSort
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

        // Shown at the size and spacing chosen, so the choice is visible where
        // it is made rather than two screens away.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_persian_font), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.help_persian_font),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = reading.persianFont, onCheckedChange = viewModel::setPersianFont)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_stylus), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_stylus_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = reading.stylusSpotlight, onCheckedChange = viewModel::setStylusSpotlight)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_hide_done), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.help_hide_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = reading.hideCompletedTasks,
                onCheckedChange = viewModel::setHideCompletedTasks,
            )
        }
    }

    SectionCard(stringResource(R.string.card_behaviour)) {
        Text(stringResource(R.string.settings_open_on), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private val StartScreen.label: Int
    get() =
        when (this) {
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
