package me.parham1995.notes.feature.sync

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.parham1995.notes.data.ImagePolicy
import me.parham1995.notes.data.SyncTransport
import java.text.DateFormat
import java.text.SimpleDateFormat
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
            TransportCard(state, viewModel)
            if (state.settings.transport == SyncTransport.REST) {
                TokenCard(state, viewModel)
            } else {
                SshKeyCard(state, viewModel)
            }
            ImagesCard(state, viewModel)
            StatusCard(state, viewModel)
            LogCard(viewModel)
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

        if (state.queued) {
            // A job waiting to retry is not a job doing work, and showing a
            // spinner for it is what made a stuck sync look like a hang.
            Text(
                text =
                    if (state.attempt > 0) {
                        "Waiting to retry (attempt ${state.attempt + 1})"
                    } else {
                        "Queued - waiting for the network"
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
                Text("${state.done} of ${state.total}", style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Checking for changes", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.lastError?.takeIf { !state.running }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // Say why syncing is not possible yet. A greyed-out button with no
        // reason reads as a missing button.
        state.blocker()?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::syncNow,
                enabled = state.blocker() == null && !state.running,
            ) {
                Text("Sync now")
            }
            if (state.running || state.queued) {
                OutlinedButton(onClick = viewModel::cancelSync) { Text("Cancel") }
            } else {
                OutlinedButton(onClick = viewModel::refreshLocal) { Text("Refresh") }
            }
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

@Composable
private fun TransportCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    SectionCard("Sync method") {
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
                        text = if (option == SyncTransport.REST) "REST (token)" else "git over SSH (key)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text =
                            if (option == SyncTransport.REST) {
                                "Markdown only. A refresh with nothing new costs one request."
                            } else {
                                "No token to rotate, but git cannot fetch a subset -- full history and all attachments."
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
private fun SshKeyCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    SectionCard("SSH key") {
        Text(
            "Generated on this device. The private half never leaves it -- add the public line below to the " +
                "repository as a read-only deploy key.",
            style = MaterialTheme.typography.bodySmall,
        )
        state.sshPublicKey?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.settings.sshOverPort443,
                        onClick = { viewModel.setSshOverPort443(!state.settings.sshOverPort443) },
                    ).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = state.settings.sshOverPort443, onCheckedChange = null)
            Column {
                Text("Connect over port 443", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Many mobile networks block port 22, where a clone simply hangs. " +
                        "GitHub answers SSH on 443 too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = viewModel::generateSshKey, enabled = !state.generatingKey) {
                Text(if (state.sshPublicKey == null) "Generate key" else "Regenerate")
            }
            state.sshPublicKey?.let { line ->
                OutlinedButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipData.newPlainText("ssh public key", line).toClipEntry())
                    }
                }) {
                    Text("Copy")
                }
            }
        }
    }
}

@Composable
private fun ImagesCard(
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

/**
 * Why a sync cannot start, or null when it can.
 *
 * The credential depends on the transport: REST needs a token, SSH needs a
 * generated key. Requiring a token in both cases -- as this did -- leaves the
 * button permanently disabled for anyone using SSH, which is exactly the setup
 * where there is deliberately no token to have.
 */
private fun SyncUiState.blocker(): String? =
    when {
        !settings.isConfigured -> "Set the repository owner and name first."
        settings.transport == SyncTransport.REST && !hasToken ->
            "REST sync needs an access token. Add one above, or switch to git over SSH."
        settings.transport == SyncTransport.SSH && sshPublicKey == null ->
            "SSH sync needs a key. Generate one above and add it to the repository as a read-only deploy key."
        else -> null
    }

/**
 * The sync journal.
 *
 * A sync runs in the background and takes minutes on its first run, so "it did
 * not work" is otherwise all there is to go on. This shows what each step
 * actually did, on the device, without a cable.
 */
@Composable
private fun LogCard(viewModel: SyncViewModel) {
    val entries by viewModel.log.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val stamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val context = LocalContext.current

    SectionCard("Sync log") {
        if (entries.isEmpty()) {
            Text(
                "Nothing recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        val shown = if (expanded) entries else entries.take(COLLAPSED_LOG_LINES)
        shown.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = time.format(Date(entry.at)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when (entry.level) {
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "WARN" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.size > COLLAPSED_LOG_LINES) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show all ${entries.size}")
                }
            }
            // Reading a log off a phone screen and retyping it is the reason a
            // journal goes unread. One tap sends the whole thing anywhere.
            TextButton(onClick = {
                val text =
                    entries.reversed().joinToString("\n") { entry ->
                        stamp.format(Date(entry.at)) + "  " + entry.level + "  " + entry.message
                    }
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "notes-droid sync log")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                context.startActivity(Intent.createChooser(send, "Share sync log"))
            }) {
                Text("Share")
            }
            TextButton(onClick = viewModel::clearLog) { Text("Clear") }
        }
    }
}

private const val COLLAPSED_LOG_LINES = 12
