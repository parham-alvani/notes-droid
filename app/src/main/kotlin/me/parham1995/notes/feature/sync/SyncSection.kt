package me.parham1995.notes.feature.sync

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.ImagePolicy
import me.parham1995.notes.data.VaultSettings
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun StatusCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    SectionCard("Status") {
        LabelledValue("Notes", state.noteCount.toString())
        // Measured here, where it is shown, rather than every time any settings
        // screen opens.
        LaunchedEffect(Unit) { viewModel.measureDisk() }
        LabelledValue("On disk", state.diskBytes?.let(::formatBytes) ?: "-")
        LabelledValue("Commit", state.headCommit?.take(SHORT_SHA_LENGTH) ?: "never synced")
        LabelledValue(
            "Last sync",
            state.lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "-",
        )

        if (state.queued) {
            // A job waiting to retry is not a job doing work, and showing a
            // spinner for it is what made a stuck sync look like a hang.
            Text(
                text =
                    if (state.attempt > 0) {
                        stringResource(R.string.sync_retry_waiting, state.attempt + 1)
                    } else {
                        stringResource(R.string.sync_queued)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        if (state.running) {
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.settings_sync_progress, state.done, state.total),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.settings_checking), style = MaterialTheme.typography.bodySmall)
            }
        }

        state.lastError?.takeIf { !state.running }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // Say why syncing is not possible yet. A greyed-out button with no
        // reason reads as a missing button.
        state.blocker()?.let { reason ->
            Text(
                text = reason.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::syncNow,
                enabled = state.blocker() == null && !state.running,
            ) {
                Text(stringResource(R.string.browse_sync_now))
            }
            if (state.running || state.queued) {
                OutlinedButton(onClick = viewModel::cancelSync) { Text(stringResource(R.string.action_cancel)) }
            } else {
                OutlinedButton(onClick = viewModel::refreshLocal) { Text(stringResource(R.string.action_refresh)) }
            }
            TextButton(
                onClick = viewModel::reindex,
                enabled = !state.running && !state.reindexing && state.noteCount > 0,
            ) {
                Text(if (state.reindexing) "Reindexing..." else "Reindex")
            }
            TextButton(
                onClick = viewModel::reset,
                enabled = !state.running,
            ) { Text(stringResource(R.string.settings_reset_vault)) }
        }
    }
}

/**
 * The scheduled refresh.
 *
 * Unlike a tap, this runs as an ordinary background job with no notification --
 * which is what Android actually permits a backgrounded app to do, and the
 * reason a scheduled sync no longer reports a foreground-service error it was
 * never going to get.
 *
 * The battery row is here because on a phone that aggressively dozes, this is
 * the single setting that decides whether any of it happens. Saying so is
 * better than a schedule that silently never fires.
 */
@Composable
internal fun BackgroundSyncCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val context = LocalContext.current
    SectionCard("Background sync") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_schedule), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.settings.backgroundSync,
                onCheckedChange = viewModel::setBackgroundSync,
            )
        }

        if (state.settings.backgroundSync) {
            Text(stringResource(R.string.settings_how_often), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultSettings.INTERVAL_CHOICES.forEach { hours ->
                    FilterChip(
                        selected = state.settings.syncIntervalHours == hours,
                        onClick = { viewModel.setSyncIntervalHours(hours) },
                        label = { Text(if (hours == HOURS_IN_DAY) "daily" else "${hours}h") },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_wifi_only), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.settings.syncOnWifiOnly,
                    onCheckedChange = viewModel::setSyncOnWifiOnly,
                )
            }

            val exempt = remember { context.ignoresBatteryOptimisations() }
            if (!exempt) {
                Text(
                    stringResource(R.string.help_battery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        runCatching {
                            // The settings list rather than the direct request,
                            // which needs a permission Play forbids and this
                            // app has no need of.
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_battery))
                }
            }
        }
    }
}

private fun Context.ignoresBatteryOptimisations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: false

private const val SHORT_SHA_LENGTH = 7
private const val HOURS_IN_DAY = 24
private const val BYTES_PER_UNIT = 1024.0

private fun formatBytes(bytes: Long): String {
    if (bytes < BYTES_PER_UNIT) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / BYTES_PER_UNIT
    var index = 0
    while (value >= BYTES_PER_UNIT && index < units.lastIndex) {
        value /= BYTES_PER_UNIT
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

@Composable
internal fun ImagesCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    SectionCard("Images") {
        ImagePolicy.entries.forEach { option ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.settings.imagePolicy == option,
                            onClick = { viewModel.setImagePolicy(option) },
                        ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = state.settings.imagePolicy == option, onClick = null)
                Text(
                    text =
                        when (option) {
                            ImagePolicy.ON_DEMAND -> "On demand"
                            ImagePolicy.PREFETCH_ON_WIFI -> "Prefetch on Wi-Fi"
                            ImagePolicy.NEVER -> "Never"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
