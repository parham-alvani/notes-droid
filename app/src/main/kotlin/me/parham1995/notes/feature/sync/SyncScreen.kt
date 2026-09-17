package me.parham1995.notes.feature.sync

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The access token is on screen here, so keep it out of the recents
    // thumbnail and out of screenshots.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Vault sync") }) }) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RepositoryCard(state, viewModel)
            TokenCard(state, viewModel)
            StatusCard(state, viewModel)
        }
    }
}

@Composable
private fun RepositoryCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    var owner by remember(state.settings.owner) { mutableStateOf(state.settings.owner) }
    var repo by remember(state.settings.repo) { mutableStateOf(state.settings.repo) }
    var branch by remember(state.settings.branch) { mutableStateOf(state.settings.branch.orEmpty()) }

    SectionCard("Repository") {
        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it },
            label = { Text("Owner") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("Repository") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("Branch") },
            placeholder = { Text("default branch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.saveRepository(owner, repo, branch) },
            enabled = owner.isNotBlank() && repo.isNotBlank(),
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun TokenCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    var token by remember { mutableStateOf("") }

    SectionCard("Access token") {
        Text(
            "A fine-grained token with Contents: read-only on this repository, and nothing else.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (state.hasToken) "Replace token" else "Token") },
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
                Text("Save")
            }
            OutlinedButton(onClick = viewModel::testConnection, enabled = state.hasToken) {
                Text("Test connection")
            }
            if (state.hasToken) {
                TextButton(onClick = viewModel::clearToken) { Text("Clear") }
            }
        }
        if (state.tokenRejected) {
            Text(
                "The token was rejected. Fine-grained tokens expire after at most a year -- this one " +
                    "may simply have run out.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.connectionMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun StatusCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    SectionCard("Status") {
        LabelledValue("Notes", state.noteCount.toString())
        LabelledValue("On disk", formatBytes(state.diskBytes))
        LabelledValue("Commit", state.headCommit?.take(SHORT_SHA_LENGTH) ?: "never synced")
        LabelledValue(
            "Last sync",
            state.lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "-",
        )

        if (state.running) {
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${state.done} of ${state.total}", style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Checking for changes", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.lastError?.takeIf { !state.running }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::syncNow,
                enabled = state.settings.isConfigured && state.hasToken && !state.running,
            ) {
                Text("Sync now")
            }
            OutlinedButton(onClick = viewModel::refreshLocal) { Text("Refresh") }
            TextButton(onClick = viewModel::reset, enabled = !state.running) { Text("Reset vault") }
        }
    }
}

@Composable
private fun SectionCard(
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
private fun LabelledValue(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private const val SHORT_SHA_LENGTH = 7
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
