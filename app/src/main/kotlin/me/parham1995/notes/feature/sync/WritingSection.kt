package me.parham1995.notes.feature.sync

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.EditKind
import me.parham1995.notes.data.VaultOverlay
import me.parham1995.notes.data.database.PendingEditEntity
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
        // The vault's author, which a commit takes where the app's own is empty.
        val vaults = state.settings.fromVault
        if (VaultOverlay.AUTHOR_NAME in vaults || VaultOverlay.AUTHOR_EMAIL in vaults) {
            Text(
                stringResource(
                    R.string.from_vault_author,
                    listOf(vaults.authorName, vaults.authorEmail).filter { it.isNotEmpty() }.joinToString(" · "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (!state.settings.author.isUsable) {
            Text(
                stringResource(R.string.help_author_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        // Who Mark as Verified names on a concept of the Open Knowledge Format.
        var actor by remember(stored.okfActor) { mutableStateOf(stored.okfActor) }
        OutlinedTextField(
            value = actor,
            onValueChange = { actor = it },
            label = { Text(stringResource(R.string.settings_okf_actor)) },
            placeholder = {
                Text(
                    state.settings.author.email
                        .substringBefore('@', ""),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.help_okf_actor),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { viewModel.setOkfActor(actor) },
            enabled = actor.trim().removePrefix("human:").trim() != stored.okfActor,
        ) {
            Text(stringResource(R.string.settings_okf_actor) + ": " + stringResource(R.string.action_save))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        state.vaults.forEach { vault ->
            LabelledValue(
                label = vault.label,
                value =
                    when {
                        // A read-only clone, whatever its key may do.
                        state.clones[vault.id]?.readOnly == true -> stringResource(R.string.read_only_badge)
                        vault.canWrite -> stringResource(R.string.settings_can_write)
                        else -> stringResource(R.string.settings_read_only)
                    },
            )
        }
        state.writeMessage?.let {
            Text(it.text(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** How the editor of a note's text draws it. */
@Composable
internal fun EditorCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val fromVault = state.settings.fromVault
    SectionCard(stringResource(R.string.card_editor)) {
        SwitchRow(
            title = stringResource(R.string.settings_live_preview),
            vault = state.vaultKey(VaultOverlay.LIVE_PREVIEW, viewModel),
            help = stringResource(R.string.help_live_preview),
            checked = state.settings.reading.livePreview,
            onCheckedChange = viewModel::setLivePreview,
            enabled = !fromVault.locks(VaultOverlay.LIVE_PREVIEW),
        )
        FromVaultNote(fromVault, VaultOverlay.LIVE_PREVIEW)
        SwitchRow(
            title = stringResource(R.string.settings_format_bar),
            vault = state.vaultKey(VaultOverlay.FORMAT_BAR, viewModel),
            help = stringResource(R.string.help_format_bar),
            checked = state.settings.reading.formatBar,
            onCheckedChange = viewModel::setFormatBar,
            enabled = !fromVault.locks(VaultOverlay.FORMAT_BAR),
        )
        FromVaultNote(fromVault, VaultOverlay.FORMAT_BAR)
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
    val pathFromVault = state.settings.fromVault.locks(VaultOverlay.CAPTURE_TARGET)
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
            enabled = !pathFromVault,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        FromVaultNote(state.settings.fromVault, VaultOverlay.CAPTURE_TARGET)
        if (state.vaults.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.vaults.forEach { vault ->
                    FilterChip(
                        selected = vault.id == chosen,
                        // The vault's path is not written down as this phone's own.
                        onClick = {
                            if (pathFromVault) {
                                viewModel.setScratchpadVault(vault.id)
                            } else {
                                viewModel.setScratchpad(path, vault.id)
                            }
                        },
                        label = { Text(vault.label) },
                    )
                }
            }
        }
        Button(
            onClick = { viewModel.setScratchpad(path, chosen) },
            enabled = !pathFromVault && path.trim() != stored.scratchpadPath && path.isNotBlank(),
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
        state.queuedEdits.forEach { edit -> QueuedEditRow(edit, onDiscard = { viewModel.discardEdit(edit.id) }) }
        Button(onClick = viewModel::sendQueuedEdits) { Text(stringResource(R.string.settings_send_queued)) }
    }
}

/**
 * One edit that waits: what it is, why it waits, and Discard. A save from the
 * editor holds a note's whole text, and one that can never land -- the file
 * changed at the desk meanwhile -- is the only copy of what was typed once a
 * sync has read the file again. So it has Copy My Text, to take the text out
 * before it is discarded.
 */
@Composable
internal fun QueuedEditRow(
    edit: PendingEditEntity,
    onDiscard: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.editor_copy_label)
    Column(Modifier.fillMaxWidth()) {
        Text(edit.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Text(
            text = edit.lastError ?: edit.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (edit.kind == EditKind.EDIT.name) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText(label, edit.text).toClipEntry())
                        }
                    },
                ) { Text(stringResource(R.string.editor_copy_text)) }
            }
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.action_discard)) }
        }
    }
}
