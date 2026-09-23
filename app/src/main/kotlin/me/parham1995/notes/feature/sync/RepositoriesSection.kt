package me.parham1995.notes.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.text

@Composable
internal fun RepositoryCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var vaultName by remember { mutableStateOf("") }
    val first = state.vaults.isEmpty()

    SectionCard(stringResource(R.string.settings_section_vaults)) {
        state.vaults.forEach { vault ->
            VaultRow(vault, viewModel)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }

        Text(
            stringResource(if (first) R.string.vaults_add_first else R.string.vaults_add_another),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it },
            label = { Text(stringResource(R.string.settings_owner)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text(stringResource(R.string.settings_repository)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text(stringResource(R.string.settings_branch)) },
            placeholder = { Text(stringResource(R.string.settings_default_branch)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vaultName,
            onValueChange = { vaultName = it },
            label = { Text(stringResource(R.string.settings_name)) },
            // For reading only. It does not decide where anything is stored, so
            // it can be changed later without moving the files.
            placeholder = { Text(repo.ifBlank { stringResource(R.string.vaults_name_placeholder) }) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                viewModel.addVault(owner, repo, branch, vaultName)
                owner = ""
                repo = ""
                branch = ""
                vaultName = ""
            },
            enabled = owner.isNotBlank() && repo.isNotBlank(),
        ) {
            Text(stringResource(R.string.action_add))
        }

        if (state.vaults.size > 1) {
            Text(
                stringResource(R.string.help_vaults),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VaultRow(
    vault: VaultEntity,
    viewModel: SyncViewModel,
) {
    val failed = vault.lastError?.let { stringResource(R.string.vault_failed, it.take(ERROR_PREVIEW)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(vault.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text =
                    listOfNotNull(
                        "${vault.owner}/${vault.repo}".takeIf { it != vault.label },
                        vault.branch,
                        failed,
                    ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (vault.lastError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Per repository, because a public one over REST and a private one over
        // SSH is a perfectly ordinary pair to want.
        FilterChip(
            selected = false,
            onClick = {
                val next =
                    if (SyncTransport.parse(vault.transport) == SyncTransport.REST) {
                        SyncTransport.SSH
                    } else {
                        SyncTransport.REST
                    }
                viewModel.setVaultTransport(vault, next)
            },
            label = { Text(vault.transport) },
        )
        IconButton(onClick = { viewModel.removeVault(vault.id) }) {
            LucideGlyph(
                "trash-2",
                size = 18.dp,
                contentDescription = stringResource(R.string.settings_remove_vault, vault.label),
            )
        }
    }
}

@Composable
internal fun TokenCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    var token by remember { mutableStateOf("") }

    SectionCard(stringResource(R.string.card_token)) {
        Text(
            stringResource(R.string.help_token),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(if (state.hasToken) R.string.token_replace else R.string.token_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    viewModel.saveToken(token)
                    token = ""
                },
                enabled = token.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
            OutlinedButton(onClick = viewModel::testConnection, enabled = state.hasToken) {
                Text(stringResource(R.string.settings_test_connection))
            }
            if (state.hasToken) {
                TextButton(onClick = viewModel::clearToken) { Text(stringResource(R.string.action_clear)) }
            }
        }
        if (state.tokenRejected) {
            Text(
                stringResource(R.string.help_token_rejected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.connectionMessage?.let {
            Text(it.text(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

private const val ERROR_PREVIEW = 60

@Composable
internal fun TransportCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    SectionCard(stringResource(R.string.card_transport)) {
        SyncTransport.entries.forEach { option ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.settings.transport == option,
                            onClick = { viewModel.setTransport(option) },
                        ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = state.settings.transport == option, onClick = null)
                Column {
                    Text(
                        text =
                            stringResource(
                                if (option == SyncTransport.REST) R.string.transport_rest else R.string.transport_ssh,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text =
                            if (option == SyncTransport.REST) {
                                stringResource(R.string.help_transport_rest)
                            } else {
                                stringResource(R.string.help_transport_ssh)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SshKeyCard(
    state: SyncUiState,
    onManage: () -> Unit,
) {
    // A summary and a way through, rather than the keys themselves. The panel
    // owns the detail; two screens showing the same thing is two screens to
    // keep in step.
    SectionCard(stringResource(R.string.ssh_title)) {
        val missing = state.sshKeys.count { it.publicKey == null }
        Text(
            text =
                when {
                    state.sshKeys.isEmpty() -> stringResource(R.string.ssh_summary_none)
                    missing > 0 -> stringResource(R.string.ssh_summary_missing, missing, state.sshKeys.size)
                    state.sshKeys.size == 1 -> stringResource(R.string.ssh_summary_one, state.sshKeys.first().label)
                    else -> stringResource(R.string.ssh_summary_many, state.sshKeys.size)
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        val noKey = stringResource(R.string.ssh_no_key_yet)
        state.sshKeys.forEach { key ->
            Text(
                text = key.label + " · " + (key.fingerprint ?: noKey),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color =
                    if (key.publicKey == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onManage) { Text(stringResource(R.string.settings_manage_keys)) }
    }
}
