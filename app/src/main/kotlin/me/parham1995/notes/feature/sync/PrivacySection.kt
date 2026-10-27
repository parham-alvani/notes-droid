package me.parham1995.notes.feature.sync

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import me.parham1995.notes.R
import me.parham1995.notes.ui.lock.Unlocker

/**
 * The app lock.
 *
 * Turning it on asks for the fingerprint first. A lock switched on by
 * someone who cannot open it -- a phone with no screen lock, a finger that
 * was never enrolled -- is found out the next time the app starts, which is
 * the one moment it cannot be switched off again.
 */
@Composable
internal fun PrivacyCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val privacy = state.settings.privacy
    SectionCard(stringResource(R.string.card_app_lock)) {
        // A tap on the row goes through the same fingerprint as the switch
        // did: the row is the control now, so it is the one that asks.
        SwitchRow(
            title = stringResource(R.string.settings_app_lock),
            checked = privacy.appLock,
            onCheckedChange = { wanted ->
                when {
                    !wanted -> viewModel.setAppLock(false)
                    activity == null -> Unit
                    !Unlocker.canLock(context) ->
                        Toast
                            .makeText(
                                context,
                                resources.getString(R.string.app_lock_no_screen_lock),
                                Toast.LENGTH_LONG,
                            ).show()
                    else ->
                        Unlocker.ask(activity, resources.getString(R.string.lock_prompt_title)) {
                            viewModel.setAppLock(true)
                        }
                }
            },
        )
        Text(
            stringResource(R.string.help_app_lock),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (privacy.appLock) {
            SwitchRow(
                title = stringResource(R.string.settings_lock_picker),
                checked = privacy.lockPicker,
                onCheckedChange = viewModel::setLockPicker,
            )
        }
    }
}
