package me.parham1995.notes.feature.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.BuildConfig
import me.parham1995.notes.R
import me.parham1995.notes.data.BrowserSort
import me.parham1995.notes.data.ImagePolicy
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.data.StartScreen
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What settings there are, grouped.
 *
 * They used to be one scroll of eleven cards, which was tolerable at five and
 * stopped being so as reading, notifications and several repositories arrived.
 * Grouping is not decoration here: the sync settings are configured once and
 * the reading settings are adjusted often, and a list that shows both at once
 * makes you scroll past the ones you never touch to reach the ones you do.
 */
enum class SettingsSection(
    val title: String,
    val summary: String,
    val icon: String,
) {
    REPOSITORIES("Vaults", "The repositories you read, and how", "library"),
    READING("Reading", "Text size, theme, where the app opens", "book-open-text"),
    SYNC("Sync", "When it refreshes, and what it fetches", "refresh-cw"),
    NOTIFICATIONS("Notifications", "The daily task summary", "bell"),
    ADVANCED("Advanced", "The journal, storage, and what this build is", "wrench"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onOpenSection: (SettingsSection) -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // Anything actively wrong is said here rather than hidden one level
            // down: a vault that cannot sync should not need exploring to find
            // out why.
            state.blocker()?.let { blocker ->
                Text(
                    text = blocker,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HorizontalDivider()
            }

            SettingsSection.entries.forEach { section ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSection(section) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LucideGlyph(section.icon, size = 22.dp, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(section.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            section.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LucideGlyph("chevron-right", size = 18.dp)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    onManageSshKeys: () -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The access token is on screen here, so keep it out of the recents
    // thumbnail and out of screenshots.
    val context = LocalContext.current
    DisposableEffect(section) {
        val secret = section == SettingsSection.REPOSITORIES
        val window = (context as? Activity)?.window
        if (secret) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { if (secret) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        LucideGlyph(
                            "arrow-left",
                            size = 22.dp,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
            when (section) {
                SettingsSection.REPOSITORIES -> {
                    RepositoryCard(state, viewModel)
                    TransportCard(state, viewModel)
                    if (state.settings.transport == SyncTransport.REST) {
                        TokenCard(state, viewModel)
                    } else {
                        SshKeyCard(state, onManageSshKeys)
                    }
                }

                SettingsSection.READING -> ReadingCard(state, viewModel)

                SettingsSection.SYNC -> {
                    BackgroundSyncCard(state, viewModel)
                    ImagesCard(state, viewModel)
                    StatusCard(state, viewModel)
                }

                SettingsSection.NOTIFICATIONS -> TaskDigestCard(state, viewModel)

                SettingsSection.ADVANCED -> {
                    state.lastCrash?.let { CrashCard(it, viewModel) }
                    LogCard(viewModel)
                    AboutCard()
                }
            }
        }
    }
}

@Composable
private fun RepositoryCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var vaultName by remember { mutableStateOf("") }
    val first = state.vaults.isEmpty()

    SectionCard("Vaults") {
        state.vaults.forEach { vault ->
            VaultRow(vault, viewModel)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }

        Text(
            if (first) "Add the repository your vault lives in." else "Add another vault",
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
            placeholder = { Text(repo.ifBlank { "what to call it" }) },
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
                "Each vault is separate: its own files, search, tasks and links. The browser " +
                    "switches between them.",
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
                        vault.lastError?.let { "failed: " + it.take(ERROR_PREVIEW) },
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
                Text(
                    stringResource(R.string.settings_sync_progress, state.done, state.total),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.settings_checking), style = MaterialTheme.typography.bodySmall)
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
                Text(stringResource(R.string.browse_sync_now))
            }
            if (state.running || state.queued) {
                OutlinedButton(onClick = viewModel::cancelSync) { Text(stringResource(R.string.action_cancel)) }
            } else {
                OutlinedButton(onClick = viewModel::refreshLocal) { Text(stringResource(R.string.action_refresh)) }
            }
            TextButton(
                onClick = viewModel::reindex,
                enabled = !state.running && !state.reindexing && state.noteCount > 0,
            ) {
                Text(if (state.reindexing) "Reindexing..." else "Reindex")
            }
            TextButton(
                onClick = viewModel::reset,
                enabled = !state.running,
            ) { Text(stringResource(R.string.settings_reset_vault)) }
        }
    }
}

/**
 * The scheduled refresh.
 *
 * Unlike a tap, this runs as an ordinary background job with no notification --
 * which is what Android actually permits a backgrounded app to do, and the
 * reason a scheduled sync no longer reports a foreground-service error it was
 * never going to get.
 *
 * The battery row is here because on a phone that aggressively dozes, this is
 * the single setting that decides whether any of it happens. Saying so is
 * better than a schedule that silently never fires.
 */
@Composable
private fun BackgroundSyncCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val context = LocalContext.current
    SectionCard("Background sync") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_schedule), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.settings.backgroundSync,
                onCheckedChange = viewModel::setBackgroundSync,
            )
        }

        if (state.settings.backgroundSync) {
            Text(stringResource(R.string.settings_how_often), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultSettings.INTERVAL_CHOICES.forEach { hours ->
                    FilterChip(
                        selected = state.settings.syncIntervalHours == hours,
                        onClick = { viewModel.setSyncIntervalHours(hours) },
                        label = { Text(if (hours == HOURS_IN_DAY) "daily" else "${hours}h") },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_wifi_only), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.settings.syncOnWifiOnly,
                    onCheckedChange = viewModel::setSyncOnWifiOnly,
                )
            }

            val exempt = remember { context.ignoresBatteryOptimisations() }
            if (!exempt) {
                Text(
                    "Battery optimisation is on for this app, so Android may defer or skip a " +
                        "scheduled sync indefinitely. Pull to refresh always works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        runCatching {
                            // The settings list rather than the direct request,
                            // which needs a permission Play forbids and this
                            // app has no need of.
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_battery))
                }
            }
        }
    }
}

/**
 * The daily task summary.
 *
 * A count, not a list. The vault this was built for has 120 overdue tasks, and
 * both a notification per task and a notification listing them are unreadable;
 * the number is the message, and the list is one tap away.
 */
@Composable
private fun TaskDigestCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    SectionCard("Daily task summary") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_digest), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.settings.taskDigest, onCheckedChange = viewModel::setTaskDigest)
        }

        if (state.settings.taskDigest) {
            Text("At", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultSettings.DIGEST_HOUR_CHOICES.forEach { hour ->
                    FilterChip(
                        selected = state.settings.taskDigestHour == hour,
                        onClick = { viewModel.setTaskDigestHour(hour) },
                        label = { Text(stringResource(R.string.settings_hour, hour)) },
                    )
                }
            }
            Text(
                "Counts what is overdue and due today, from the last sync. Nothing is sent " +
                    "when there is nothing to report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Context.ignoresBatteryOptimisations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: false

/**
 * Shown only after a crash, and only until it is dismissed.
 *
 * There is no store console behind this app and logcat is gone by the time
 * anyone thinks to look, so the alternative to putting it here is losing the
 * reason entirely.
 */
@Composable
private fun CrashCard(
    crash: String,
    viewModel: SyncViewModel,
) {
    val context = LocalContext.current
    val crashSubject = stringResource(R.string.settings_crash_subject)
    SectionCard("The app crashed") {
        Text(
            crash.lineSequence().take(CRASH_PREVIEW_LINES).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = CRASH_PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, crashSubject)
                                putExtra(Intent.EXTRA_TEXT, crash)
                            },
                            "Share the crash",
                        ),
                    )
                }
            }) {
                Text(stringResource(R.string.action_share))
            }
            OutlinedButton(onClick = viewModel::dismissCrash) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

/**
 * What this build actually is.
 *
 * Useful when more than one APK is in circulation -- a release from the tag, a
 * build handed over directly -- and the only way to tell them apart used to be
 * to look at the file you installed from. The date is the commit's, not the
 * moment of the build, so two builds of the same source say the same thing.
 */
@Composable
private fun AboutCard() {
    val context = LocalContext.current
    SectionCard("About") {
        LabelledValue("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        LabelledValue("Released", BuildConfig.BUILD_DATE)
        LabelledValue("Commit", BuildConfig.GIT_SHA)
        LabelledValue("Author", BuildConfig.AUTHOR)
        LabelledValue("License", BuildConfig.LICENSE)
        // The bundled font is separately licensed and the OFL asks that it be
        // acknowledged wherever the software is.
        LabelledValue("Persian type", "Vazirmatn, OFL 1.1")
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.REPOSITORY.toUri()))
                }
            },
        ) {
            Text(BuildConfig.REPOSITORY.removePrefix("https://"))
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
private const val HOURS_IN_DAY = 24
private const val ERROR_PREVIEW = 60
private const val CRASH_PREVIEW_LINES = 8
private const val PERCENT = 100
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
    onManage: () -> Unit,
) {
    // A summary and a way through, rather than the keys themselves. The panel
    // owns the detail; two screens showing the same thing is two screens to
    // keep in step.
    SectionCard("SSH keys") {
        val missing = state.sshKeys.count { it.publicKey == null }
        Text(
            text =
                when {
                    state.sshKeys.isEmpty() -> "No repository syncs over SSH."
                    missing > 0 -> "$missing of ${state.sshKeys.size} still need a key."
                    state.sshKeys.size == 1 -> "One key, for ${state.sshKeys.first().label}."
                    else -> "${state.sshKeys.size} keys, one per repository."
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        state.sshKeys.forEach { key ->
            Text(
                text = key.label + " · " + (key.fingerprint ?: "no key yet"),
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

/**
 * How notes are set.
 *
 * The first settings in this app that are about reading rather than syncing,
 * which for a reader was an odd gap: nine settings, all of them about how the
 * vault arrives and none about how it looks once it has.
 */
@Composable
private fun ReadingCard(
    state: SyncUiState,
    viewModel: SyncViewModel,
) {
    val reading = state.settings.reading

    SectionCard("Text") {
        Text(stringResource(R.string.settings_size), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingSettings.TEXT_SCALES.forEach { scale ->
                FilterChip(
                    selected = reading.textScale == scale,
                    onClick = { viewModel.setTextScale(scale) },
                    label = { Text(stringResource(R.string.settings_percent, (scale * PERCENT).toInt())) },
                )
            }
        }

        Text(stringResource(R.string.settings_line_spacing), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingSettings.LINE_SPACINGS.forEach { spacing ->
                FilterChip(
                    selected = reading.lineSpacing == spacing,
                    onClick = { viewModel.setLineSpacing(spacing) },
                    label = { Text(if (spacing == 1f) "normal" else "${(spacing * PERCENT).toInt()}%") },
                )
            }
        }

        // Shown at the size and spacing chosen, so the choice is visible where
        // it is made rather than two screens away.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "The quick brown fox. " + (if (reading.persianFont) "روباه قهوه‌ای چابک." else "روباه قهوه‌ای چابک."),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge.inScript(),
            )
        }
    }

    SectionCard("Appearance") {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice.entries.forEach { choice ->
                FilterChip(
                    selected = reading.theme == choice,
                    onClick = { viewModel.setTheme(choice) },
                    label = { Text(choice.name.lowercase()) },
                )
            }
        }
        Text(
            "naz is a dark colourscheme and defines no light palette, so light keeps its accents " +
                "against paper rather than pretending to be it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_persian_font), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Off uses the platform's Naskh, which has every glyph and draws them differently.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = reading.persianFont, onCheckedChange = viewModel::setPersianFont)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_stylus), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_stylus_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = reading.stylusSpotlight, onCheckedChange = viewModel::setStylusSpotlight)
        }
    }

    SectionCard("Behaviour") {
        Text(stringResource(R.string.settings_open_on), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StartScreen.entries.forEach { screen ->
                FilterChip(
                    selected = reading.startScreen == screen,
                    onClick = { viewModel.setStartScreen(screen) },
                    label = { Text(screen.name.lowercase()) },
                )
            }
        }

        Text(stringResource(R.string.settings_folder_order), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserSort.entries.forEach { sort ->
                FilterChip(
                    selected = reading.browserSort == sort,
                    onClick = { viewModel.setBrowserSort(sort) },
                    label = { Text(sort.name.lowercase().replace('_', ' ')) },
                )
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
private fun SyncUiState.blocker(): String? {
    val overRest = vaults.any { SyncTransport.parse(it.transport) == SyncTransport.REST }
    val keyless = sshKeys.firstOrNull { it.publicKey == null }
    return when {
        vaults.isEmpty() -> "Add a repository first."
        overRest && !hasToken ->
            "Syncing over REST needs an access token. Add one above, or switch that repository to SSH."
        keyless != null ->
            "${keyless.label} has no SSH key yet. Generate one above and add it to that repository " +
                "as a read-only deploy key."
        else -> null
    }
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
    val logSubject = stringResource(R.string.settings_log_subject)

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
                        putExtra(Intent.EXTRA_SUBJECT, logSubject)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                context.startActivity(Intent.createChooser(send, "Share sync log"))
            }) {
                Text(stringResource(R.string.action_share))
            }
            TextButton(onClick = viewModel::clearLog) { Text(stringResource(R.string.action_clear)) }
        }
    }
}

private const val COLLAPSED_LOG_LINES = 12
