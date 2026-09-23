package me.parham1995.notes.feature.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
