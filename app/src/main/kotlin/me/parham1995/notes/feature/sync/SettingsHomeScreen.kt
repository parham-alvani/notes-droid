package me.parham1995.notes.feature.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph

/**
 * What settings there are, grouped.
 *
 * They used to be one scroll of eleven cards, which was tolerable at five and
 * stopped being so as reading, notifications and several repositories arrived.
 * Grouping is not decoration here: the sync settings are configured once and
 * the reading settings are adjusted often, and a list that shows both at once
 * makes you scroll past the ones you never touch to reach the ones you do.
 */
enum class SettingsSection(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val icon: String,
) {
    REPOSITORIES(R.string.settings_section_vaults, R.string.settings_section_vaults_summary, "library"),
    READING(R.string.settings_section_reading, R.string.settings_section_reading_summary, "book-open-text"),
    WRITING(R.string.settings_section_writing, R.string.settings_section_writing_summary, "pencil"),
    SYNC(R.string.settings_section_sync, R.string.settings_section_sync_summary, "refresh-cw"),
    NOTIFICATIONS(
        R.string.settings_section_notifications,
        R.string.settings_section_notifications_summary,
        "bell",
    ),
    ADVANCED(R.string.settings_section_advanced, R.string.settings_section_advanced_summary, "wrench"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onOpenSection: (SettingsSection) -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // Anything actively wrong is said here rather than hidden one level
            // down: a vault that cannot sync should not need exploring to find
            // out why.
            state.blocker()?.let { blocker ->
                Text(
                    text = blocker.text(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HorizontalDivider()
            }

            SettingsSection.entries.forEach { section ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSection(section) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LucideGlyph(section.icon, size = 22.dp, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(section.title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(section.summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LucideGlyph("chevron-right", size = 18.dp)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}
