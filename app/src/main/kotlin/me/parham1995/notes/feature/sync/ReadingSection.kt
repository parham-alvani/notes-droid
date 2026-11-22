package me.parham1995.notes.feature.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import me.parham1995.notes.data.FoldSections
import me.parham1995.notes.data.LineWidth
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.data.StartScreen
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.VaultOverlay
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
    val fromVault = state.settings.fromVault

    SectionCard(stringResource(R.string.card_text)) {
        SettingLabel(stringResource(R.string.settings_size), state.vaultKey(VaultOverlay.TEXT_SIZE, viewModel))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingSettings.TEXT_SCALES.forEach { scale ->
                FilterChip(
                    selected = reading.textScale == scale,
                    onClick = { viewModel.setTextScale(scale) },
                    enabled = !fromVault.locks(VaultOverlay.TEXT_SIZE),
                    label = { Text(stringResource(R.string.settings_percent, (scale * PERCENT).toInt())) },
                )
            }
        }

        FromVaultNote(fromVault, VaultOverlay.TEXT_SIZE)

        SettingLabel(
            stringResource(R.string.settings_line_spacing),
            state.vaultKey(VaultOverlay.LINE_SPACING, viewModel),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        SettingLabel(stringResource(R.string.settings_line_width), state.vaultKey(VaultOverlay.TEXT_WIDTH, viewModel))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineWidth.entries.forEach { width ->
                FilterChip(
                    selected = reading.lineWidth == width,
                    onClick = { viewModel.setLineWidth(width) },
                    enabled = !fromVault.locks(VaultOverlay.TEXT_WIDTH),
                    label = { Text(stringResource(width.label)) },
                )
            }
        }
        FromVaultNote(fromVault, VaultOverlay.TEXT_WIDTH)
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
        SettingLabel(stringResource(R.string.settings_theme), state.vaultKey(VaultOverlay.THEME, viewModel))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice.entries.forEach { choice ->
                FilterChip(
                    selected = reading.theme == choice,
                    onClick = { viewModel.setTheme(choice) },
                    enabled = !fromVault.locks(VaultOverlay.THEME),
                    label = { Text(stringResource(choice.label)) },
                )
            }
        }
        FromVaultNote(fromVault, VaultOverlay.THEME)
        Text(
            stringResource(R.string.help_theme),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingLabel(
            stringResource(R.string.settings_widget_background),
            state.vaultKey(VaultOverlay.WIDGET_OPACITY, viewModel),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            vault = state.vaultKey(VaultOverlay.PERSIAN_FONT, viewModel),
            help = stringResource(R.string.help_persian_font),
            checked = reading.persianFont,
            onCheckedChange = viewModel::setPersianFont,
        )
        FromVaultNote(fromVault, VaultOverlay.PERSIAN_FONT)

        SwitchRow(
            title = stringResource(R.string.settings_stylus),
            vault = state.vaultKey(VaultOverlay.STYLUS_SPOTLIGHT, viewModel),
            help = stringResource(R.string.settings_stylus_note),
            checked = reading.stylusSpotlight,
            onCheckedChange = viewModel::setStylusSpotlight,
        )
        FromVaultNote(fromVault, VaultOverlay.STYLUS_SPOTLIGHT)

        SwitchRow(
            title = stringResource(R.string.settings_hide_done),
            vault = state.vaultKey(VaultOverlay.HIDE_COMPLETED, viewModel),
            help = stringResource(R.string.help_hide_done),
            checked = reading.hideCompletedTasks,
            onCheckedChange = viewModel::setHideCompletedTasks,
        )
        FromVaultNote(fromVault, VaultOverlay.HIDE_COMPLETED)

        SwitchRow(
            title = stringResource(R.string.settings_hold_to_define),
            vault = state.vaultKey(VaultOverlay.HOLD_TO_DEFINE, viewModel),
            help = stringResource(R.string.help_hold_to_define),
            checked = reading.holdToDefine,
            onCheckedChange = viewModel::setHoldToDefine,
        )
        FromVaultNote(fromVault, VaultOverlay.HOLD_TO_DEFINE)

        SwitchRow(
            title = stringResource(R.string.settings_soft_dark),
            vault = state.vaultKey(VaultOverlay.DARK_PALETTE, viewModel),
            help = stringResource(R.string.help_soft_dark),
            checked = reading.softDark,
            onCheckedChange = viewModel::setSoftDark,
            enabled = !fromVault.locks(VaultOverlay.DARK_PALETTE),
        )
        FromVaultNote(fromVault, VaultOverlay.DARK_PALETTE)

        SwitchRow(
            title = stringResource(R.string.settings_remote_images),
            vault = state.vaultKey(VaultOverlay.REMOTE_IMAGES, viewModel),
            help = stringResource(R.string.help_remote_images),
            checked = reading.remoteImages,
            onCheckedChange = viewModel::setRemoteImages,
            enabled = !fromVault.locks(VaultOverlay.REMOTE_IMAGES),
        )
        FromVaultNote(fromVault, VaultOverlay.REMOTE_IMAGES)
    }

    SectionCard(stringResource(R.string.card_behaviour)) {
        SettingLabel(stringResource(R.string.settings_open_on), state.vaultKey(VaultOverlay.START_SCREEN, viewModel))
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

        SettingLabel(
            stringResource(R.string.settings_folder_order),
            state.vaultKey(VaultOverlay.BROWSER_SORT, viewModel),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserSort.entries.forEach { sort ->
                FilterChip(
                    selected = reading.browserSort == sort,
                    onClick = { viewModel.setBrowserSort(sort) },
                    label = { Text(stringResource(sort.label)) },
                )
            }
        }

        SettingLabel(
            stringResource(R.string.settings_fold_sections),
            state.vaultKey(VaultOverlay.FOLD_SECTIONS, viewModel),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FoldSections.entries.forEach { choice ->
                FilterChip(
                    selected = reading.foldSections == choice,
                    onClick = { viewModel.setFoldSections(choice) },
                    enabled = !fromVault.locks(VaultOverlay.FOLD_SECTIONS),
                    label = { Text(stringResource(choice.label)) },
                )
            }
        }
        FromVaultNote(fromVault, VaultOverlay.FOLD_SECTIONS)
        Text(
            stringResource(R.string.help_fold_sections),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Nothing is hidden while hidden files are shown, so this would
        // be a switch that does nothing.
        SwitchRow(
            title = stringResource(R.string.settings_hide_agent_files),
            vault = state.vaultKey(VaultOverlay.HIDDEN_FILES, viewModel),
            help = stringResource(R.string.help_hide_agent_files),
            checked = reading.hideAgentFiles,
            onCheckedChange = viewModel::setHideAgentFiles,
            enabled = !reading.showHiddenFiles && !fromVault.locks(VaultOverlay.HIDDEN_FILES),
        )
        FromVaultNote(fromVault, VaultOverlay.HIDDEN_FILES)

        SwitchRow(
            title = stringResource(R.string.settings_show_hidden_files),
            vault = state.vaultKey(VaultOverlay.SHOW_HIDDEN, viewModel),
            help = stringResource(R.string.help_show_hidden_files),
            checked = reading.showHiddenFiles,
            onCheckedChange = viewModel::setShowHiddenFiles,
            enabled = !fromVault.locks(VaultOverlay.SHOW_HIDDEN),
        )
        FromVaultNote(fromVault, VaultOverlay.SHOW_HIDDEN)

        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            vault = state.vaultKey(VaultOverlay.KEEP_SCREEN_ON, viewModel),
            help = stringResource(R.string.help_keep_screen_on),
            checked = reading.keepScreenOn,
            onCheckedChange = viewModel::setKeepScreenOn,
        )
        FromVaultNote(fromVault, VaultOverlay.KEEP_SCREEN_ON)

        SwitchRow(
            title = stringResource(R.string.settings_tap_full_screen),
            vault = state.vaultKey(VaultOverlay.TAP_FULL_SCREEN, viewModel),
            help = stringResource(R.string.help_tap_full_screen),
            checked = reading.tapForFullScreen,
            onCheckedChange = viewModel::setTapForFullScreen,
        )
        FromVaultNote(fromVault, VaultOverlay.TAP_FULL_SCREEN)
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
private val FoldSections.label: Int
    get() =
        when (this) {
            FoldSections.VAULT -> R.string.fold_sections_vault
            FoldSections.ALWAYS -> R.string.fold_sections_always
            FoldSections.NEVER -> R.string.fold_sections_never
        }

@get:StringRes
private val BrowserSort.label: Int
    get() =
        when (this) {
            BrowserSort.NAME -> R.string.sort_name
            BrowserSort.RECENTLY_OPENED -> R.string.sort_recently_opened
            BrowserSort.RECENTLY_CHANGED -> R.string.sort_recently_changed
        }
