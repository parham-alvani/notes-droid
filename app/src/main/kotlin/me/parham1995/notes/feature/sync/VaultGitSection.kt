package me.parham1995.notes.feature.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import me.parham1995.notes.data.CloneSettings
import me.parham1995.notes.data.FromVault
import me.parham1995.notes.data.VaultOverlay
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.ui.text

/**
 * What one clone is, from its own git config: read-only, and its address
 * (`read-only-vault.md` in notes-linux's `docs/specs/`, section 4).
 *
 * Shown only for a vault that is a clone, which here is one synced over SSH.
 * The spec's Name, Email and SSH key fields are not on this card: on Android
 * the clone is the app's own, and its key and author are in the app's
 * settings already, so the card says where.
 *
 * A key that cannot write is the case the Read-only switch is for, so the
 * card says so and offers the switch outright rather than leaving the vault
 * looking writable until something is refused.
 */
@Composable
internal fun VaultGitCard(
    vault: VaultEntity,
    clone: CloneSettings,
    message: String?,
    onReadOnly: (Boolean) -> Unit,
    onAddress: (String) -> Unit,
) {
    val stored = clone.address.orEmpty()
    var address by remember(stored) { mutableStateOf(stored) }

    SectionCard(stringResource(R.string.card_git, vault.label)) {
        SwitchRow(
            title = stringResource(R.string.read_only_badge),
            help = stringResource(R.string.read_only_tooltip),
            checked = clone.readOnly,
            onCheckedChange = onReadOnly,
        )
        if (!clone.readOnly && !vault.canWrite && vault.headCommit != null) {
            Text(
                stringResource(R.string.push_refused_read_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { onReadOnly(true) }) {
                Text(stringResource(R.string.action_make_read_only))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.settings_vault_address)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.help_vault_address),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onAddress(address) }, enabled = address.trim() != stored) {
                Text(stringResource(R.string.action_save))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text(
            stringResource(R.string.help_git_identity),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The Git card of every vault that is a clone. */
@Composable
internal fun VaultGitCards(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    state.vaults.forEach { vault ->
        val clone = state.clones[vault.id] ?: return@forEach
        VaultGitCard(
            vault = vault,
            clone = clone,
            message =
                state.addressMessage
                    ?.takeIf { it.first == vault.id }
                    ?.second
                    ?.text(),
            onReadOnly = { viewModel.setReadOnly(vault.id, it) },
            onAddress = { viewModel.setAddress(vault.id, it) },
        )
    }
}

/**
 * Whether this phone follows the settings a vault carries, and which
 * (`vault-config.md` in notes-linux's `docs/specs/`, sections 1 and 4): one
 * switch for all of them, and one for each key the vault being read sets.
 */
@Composable
internal fun VaultSettingsCard(
    fromVault: FromVault,
    onFollow: (Boolean) -> Unit,
    onDecline: (key: String, declined: Boolean) -> Unit,
) {
    SectionCard(stringResource(R.string.card_vault_settings)) {
        SwitchRow(
            title = stringResource(R.string.settings_follow_vault),
            help = stringResource(R.string.help_follow_vault),
            checked = fromVault.follow,
            onCheckedChange = onFollow,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Text(
            stringResource(
                if (fromVault.offered.isEmpty()) R.string.vault_settings_none else R.string.vault_settings_sets,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        fromVault.offered.forEach { key ->
            SwitchRow(
                title = stringResource(vaultKeyLabel(key)),
                help = key,
                checked = fromVault.follow && key !in fromVault.declined,
                onCheckedChange = { follow -> onDecline(key, !follow) },
                enabled = fromVault.follow,
            )
        }
    }
}

/** What a key of the vault's `.daftar.toml` is called in this app's own settings. */
@StringRes
internal fun vaultKeyLabel(key: String): Int =
    when (key) {
        VaultOverlay.THEME -> R.string.settings_theme
        VaultOverlay.TEXT_SIZE -> R.string.settings_size
        VaultOverlay.TEXT_WIDTH -> R.string.settings_line_width
        VaultOverlay.REMOTE_IMAGES -> R.string.settings_remote_images
        VaultOverlay.FOLD_SECTIONS -> R.string.settings_fold_sections
        VaultOverlay.SYNC_INTERVAL -> R.string.settings_schedule
        VaultOverlay.AUTHOR_NAME -> R.string.settings_author_name
        VaultOverlay.AUTHOR_EMAIL -> R.string.settings_author_email
        VaultOverlay.DIGEST -> R.string.settings_digest
        VaultOverlay.DIGEST_TIME -> R.string.digest_at
        VaultOverlay.CAPTURE_TARGET -> R.string.card_scratchpad
        VaultOverlay.HIDDEN_FILES -> R.string.settings_hidden_names
        VaultOverlay.SHOW_HIDDEN -> R.string.settings_show_hidden_files
        VaultOverlay.HOME_IMAGE -> R.string.settings_home_picture
        else -> R.string.card_vault_settings
    }

/**
 * Said under a setting the vault sets, whose control is off: where the value
 * came from and how to decline it. Nothing when [key] is not the vault's.
 */
@Composable
internal fun FromVaultNote(
    fromVault: FromVault,
    vararg key: String,
) {
    if (key.none { it in fromVault }) return
    Text(
        stringResource(R.string.help_from_vault),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}
