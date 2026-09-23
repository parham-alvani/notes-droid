package me.parham1995.notes.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.VaultSettings

/**
 * The daily task summary.
 *
 * A count, not a list. The vault this was built for has 120 overdue tasks, and
 * both a notification per task and a notification listing them are unreadable;
 * the number is the message, and the list is one tap away.
 */
@Composable
internal fun TaskDigestCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    SectionCard(stringResource(R.string.card_digest)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_digest), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.settings.taskDigest, onCheckedChange = viewModel::setTaskDigest)
        }

        if (state.settings.taskDigest) {
            Text(stringResource(R.string.digest_at), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultSettings.DIGEST_HOUR_CHOICES.forEach { hour ->
                    FilterChip(
                        selected = state.settings.taskDigestHour == hour,
                        onClick = { viewModel.setTaskDigestHour(hour) },
                        label = { Text(stringResource(R.string.settings_hour, hour)) },
                    )
                }
            }
            Text(
                stringResource(R.string.help_digest),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
