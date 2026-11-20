package me.parham1995.notes.feature.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.SyncTransport

@Composable
internal fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun LabelledValue(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/**
 * A setting that is on or off: its name, what it does, and a switch.
 *
 * The whole row is the control, not just the switch at its end. A switch
 * alone is a 52dp target at the far edge of a card, and a tap on the words
 * beside it -- the natural place to tap -- did nothing at all. The switch is
 * given no handler of its own, so the row is one control to TalkBack too: it
 * reads the title, the help and the state once, instead of the words and then
 * an unnamed "switch, off" as a second stop.
 *
 * [enabled] covers the row as well as the switch, so a setting that would do
 * nothing cannot be changed by tapping beside it either.
 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    enabled: Boolean = true,
    /** A setting the vault may hold: a long press offers to keep it on this phone, or to follow the vault again. */
    vault: VaultKeyActions? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val control =
        if (vault == null || !vault.offered) {
            Modifier.toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
        } else {
            // A tap is the switch still, and says so to TalkBack; a hold is the menu.
            Modifier
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Switch,
                    onLongClick = { menu = true },
                    onClick = { onCheckedChange(!checked) },
                ).semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
        }
    if (vault != null) VaultKeyMenu(vault, expanded = menu, onDismiss = { menu = false })
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(control)
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (help != null) {
                Text(
                    help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Why a sync cannot start, or null when it can.
 *
 * The credential depends on the transport: REST needs a token, SSH needs a
 * generated key. Requiring a token in both cases -- as this did -- leaves the
 * button permanently disabled for anyone using SSH, which is exactly the setup
 * where there is deliberately no token to have.
 */
internal fun SyncUiState.blocker(): Blocker? {
    val overRest = vaults.any { SyncTransport.parse(it.transport) == SyncTransport.REST }
    val keyless = sshKeys.firstOrNull { it.publicKey == null }
    return when {
        vaults.isEmpty() -> Blocker(R.string.help_add_repository)
        overRest && !hasToken -> Blocker(R.string.help_needs_token)
        keyless != null -> Blocker(R.string.help_needs_key, keyless.label)
        else -> null
    }
}

/**
 * Which reason to give, rather than the words of it.
 *
 * This is worked out from state, not from a composition, so it cannot read a
 * resource -- and returning English from here is how the screen kept a
 * sentence the rest of it no longer has.
 */
internal data class Blocker(
    @StringRes val message: Int,
    val argument: String? = null,
)

@Composable
internal fun Blocker.text(): String =
    if (argument == null) stringResource(message) else stringResource(message, argument)

/**
 * What a long press on a setting the vault may hold offers
 * (`vault-config.md` in notes-linux's `docs/specs/`, section 5): Keep on
 * This Device, which declines the key, or Follow the Vault, which takes it
 * off the declined list again.
 */
internal class VaultKeyActions(
    val key: String,
    /** This phone keeps the key: the vault's value is not used, and a change is the phone's. */
    val declined: Boolean,
    /** Whether there is anything to choose: the vault's settings are followed at all. */
    val offered: Boolean,
    val onKeep: () -> Unit,
    val onFollow: () -> Unit,
)

internal fun SyncUiState.vaultKey(
    key: String,
    viewModel: SyncViewModel,
): VaultKeyActions =
    VaultKeyActions(
        key = key,
        declined = key in settings.fromVault.declined,
        offered = settings.fromVault.follow,
        onKeep = { viewModel.keepOnDevice(key) },
        onFollow = { viewModel.followVault(key) },
    )

@Composable
internal fun VaultKeyMenu(
    actions: VaultKeyActions,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (actions.declined) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.follow_the_vault)) },
                onClick = {
                    onDismiss()
                    actions.onFollow()
                },
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.keep_on_this_device)) },
                onClick = {
                    onDismiss()
                    actions.onKeep()
                },
            )
        }
    }
}

/** The name above a setting's choices, which a long press opens the vault's menu from. */
@Composable
internal fun SettingLabel(
    text: String,
    vault: VaultKeyActions,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier =
                if (vault.offered) {
                    Modifier.combinedClickable(onLongClick = { menu = true }, onClick = { menu = true })
                } else {
                    Modifier
                },
        )
        VaultKeyMenu(vault, expanded = menu, onDismiss = { menu = false })
    }
}
