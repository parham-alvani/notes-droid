package me.parham1995.notes.feature.ssh

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.parham1995.notes.data.git.StoredKey
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.theme.Naz
import java.text.DateFormat
import java.util.Date

/**
 * The keys, what they are for, and whether they work.
 *
 * The last part is the reason this screen exists. Until now the only way to
 * find out whether a deploy key had actually been registered was to start a
 * sync and interpret the failure -- and those failures are ambiguous enough
 * that a perfectly good key was deleted and replaced twice over one morning.
 * A handshake answers it in a couple of seconds and says so plainly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshScreen(
    onBack: () -> Unit,
    viewModel: SshViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var replacing by remember { mutableStateOf<SshKeyRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        LucideGlyph("arrow-left", size = 22.dp, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Every key is generated on this device and its private half never leaves it. " +
                            "What you copy out is the public line, which goes to the repository as a " +
                            "read-only deploy key.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "GitHub allows a deploy key on exactly one repository, so there is one key per " +
                            "repository rather than one for all of them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.loaded && state.repositories.isEmpty()) {
                Text(
                    "No repository is set to sync over SSH. Switch one to SSH in Settings and its key " +
                        "will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.repositories.forEach { row ->
                KeyCard(
                    row = row,
                    busy = state.busy,
                    onGenerate = { viewModel.generate(row.vault.name) },
                    onReplace = { replacing = row },
                    onTest = { viewModel.test(row) },
                    onCopy = { line ->
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText("ssh public key", line).toClipEntry())
                        }
                    },
                )
            }

            if (state.orphans.isNotEmpty()) {
                OrphanCard(state.orphans, viewModel::deleteOrphan)
            }

            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Connect over port 443", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Many mobile networks block port 22, where a clone simply hangs with no " +
                                "error at all. GitHub answers SSH on 443 too.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.overPort443, onCheckedChange = viewModel::setOverPort443)
                }
            }
        }
    }

    // Replacing a key is not undoable and silently breaks syncing until the new
    // line is registered, so it asks first.
    replacing?.let { row ->
        AlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text("Replace the key for ${row.label}?") },
            text = {
                Text(
                    "The key currently registered on ${row.remote} stops working immediately. " +
                        "Syncing that repository will fail until the new public line is added to it " +
                        "as a deploy key, and the old one should be removed there.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.generate(row.vault.name)
                    replacing = null
                }) {
                    Text("Replace")
                }
            },
            dismissButton = { TextButton(onClick = { replacing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyCard(
    row: SshKeyRow,
    busy: Boolean,
    onGenerate: () -> Unit,
    onReplace: () -> Unit,
    onTest: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium)
            Text(
                row.remote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val key = row.key
            if (key == null) {
                Text(
                    "No key yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onGenerate, enabled = !busy) { Text("Generate") }
                return@Column
            }

            Text(
                text = "${key.algorithm} · created ${key.createdAt.asDate()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The fingerprint, not the whole line, is what GitHub shows beside a
            // deploy key -- so it is what you compare against to answer "is this
            // the one that is registered?".
            Text(
                text = key.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = key.publicKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTest, enabled = row.check != KeyCheck.Running) {
                    Text(if (row.check == KeyCheck.Running) "Testing..." else "Test")
                }
                OutlinedButton(onClick = { onCopy(key.publicKey) }) { Text("Copy") }
                TextButton(onClick = onReplace, enabled = !busy) { Text("Replace") }
            }

            when (val check = row.check) {
                is KeyCheck.Passed -> Outcome(check.message, Naz.SpringGreen, "circle-check")
                is KeyCheck.Failed -> Outcome(check.message, Naz.Red, "circle-x")
                else -> Unit
            }
        }
    }
}

@Composable
private fun Outcome(
    message: String,
    tint: Color,
    icon: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LucideGlyph(icon, size = 16.dp, tint = tint, modifier = Modifier.padding(top = 2.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun OrphanCard(
    orphans: List<StoredKey>,
    onDelete: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Unused keys", style = MaterialTheme.typography.titleMedium)
            Text(
                "Left behind by a repository that was removed or switched to REST. Still private keys " +
                    "sitting in the app's storage, and the deploy keys they match are probably still " +
                    "registered on GitHub.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            orphans.forEach { key ->
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(key.fileName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            key.fingerprint,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onDelete(key.fileName) }) {
                        LucideGlyph("trash-2", size = 18.dp, contentDescription = "Delete ${key.fileName}")
                    }
                }
            }
        }
    }
}

private fun Long?.asDate(): String =
    this?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "unknown"
