package me.parham1995.notes.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.BuildConfig
import me.parham1995.notes.R
import me.parham1995.notes.data.SettingsStore

/**
 * What changed, once, the first time the app opens after an update.
 *
 * A sideloaded app has no store page to say so, and the release notes are
 * written anyway -- for F-Droid and the release page -- so they are shown
 * here from the same file. Not after a fresh install, which has nothing to
 * compare with: that is recorded and passed over, as is a build whose
 * changelog is empty.
 */
@Composable
fun WhatsNew(
    settings: SettingsStore,
    /** Whether a vault has been set up; without one this is a fresh install. */
    configured: Boolean,
) {
    val scope = rememberCoroutineScope()
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val seen = settings.seenVersion.first()
        when {
            seen >= BuildConfig.VERSION_CODE -> Unit
            (seen == 0 && !configured) || BuildConfig.CHANGELOG.isBlank() ->
                settings.setSeenVersion(BuildConfig.VERSION_CODE)
            else -> showing = true
        }
    }
    if (!showing) return

    fun dismiss() {
        showing = false
        scope.launch { settings.setSeenVersion(BuildConfig.VERSION_CODE) }
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.whats_new_title, BuildConfig.VERSION_NAME)) },
        text = {
            Text(
                BuildConfig.CHANGELOG,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = ::dismiss) { Text(stringResource(R.string.whats_new_ok)) } },
    )
}
