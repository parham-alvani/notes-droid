package me.parham1995.notes.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.text

/**
 * Who the app commits as, and whether it may commit at all.
 *
 * Both halves are shown together because either one alone hides every write
 * affordance in the app, and a checkbox that is simply not there is impossible
 * to diagnose from the screen it is missing from.
 */
@Composable
internal fun WritingCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val stored = state.settings.write
    var name by remember(stored.authorName) { mutableStateOf(stored.authorName) }
    var email by remember(stored.authorEmail) { mutableStateOf(stored.authorEmail) }
    val changed = name.trim() != stored.authorName || email.trim() != stored.authorEmail

    SectionCard(stringResource(R.string.card_commit_as)) {
        Text(
            stringResource(R.string.help_author),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_author_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.settings_author_email)) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.setAuthor(name, email) }, enabled = changed) {
                Text(stringResource(R.string.action_save))
            }
            OutlinedButton(onClick = viewModel::checkWriteAccess) {
                Text(stringResource(R.string.settings_check_write))
            }
        }
        if (!stored.hasAuthor) {
            Text(
                stringResource(R.string.help_author_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        state.vaults.forEach { vault ->
            LabelledValue(
                label = vault.label,
                value =
                    if (vault.canWrite) {
                        stringResource(R.string.settings_can_write)
                    } else {
                        stringResource(R.string.settings_read_only)
                    },
            )
        }
        state.writeMessage?.let {
            Text(it.text(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Where a captured thought lands, and in which vault. */
@Composable
internal fun ScratchpadCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val stored = state.settings.write
    var path by remember(stored.scratchpadPath) { mutableStateOf(stored.scratchpadPath) }
    val chosen =
        stored.scratchpadVaultId.takeIf { id -> state.vaults.any { it.id == id } }
            ?: state.vaults.firstOrNull()?.id
            ?: 0L

    SectionCard(stringResource(R.string.card_scratchpad)) {
        Text(
            stringResource(R.string.help_scratchpad),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text(stringResource(R.string.settings_scratchpad_path)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.vaults.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.vaults.forEach { vault ->
                    FilterChip(
                        selected = vault.id == chosen,
                        onClick = { viewModel.setScratchpad(path, vault.id) },
                        label = { Text(vault.label) },
                    )
                }
            }
        }
        Button(
            onClick = { viewModel.setScratchpad(path, chosen) },
            enabled = path.trim() != stored.scratchpadPath && path.isNotBlank(),
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

/**
 * Edits made here that have not reached the repository.
 *
 * Shown rather than kept quiet: an edit that is only on the device is a
 * different thing from one that has landed, and the difference matters when the
 * same file is being edited at a desk.
 */
@Composable
internal fun QueuedEditsCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    SectionCard(stringResource(R.string.card_queued)) {
        state.queuedEdits.forEach { edit ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(edit.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(
                        text = edit.lastError ?: edit.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                TextButton(onClick = { viewModel.discardEdit(edit.id) }) {
                    Text(stringResource(R.string.action_discard))
                }
            }
        }
        Button(onClick = viewModel::sendQueuedEdits) { Text(stringResource(R.string.settings_send_queued)) }
    }
}
