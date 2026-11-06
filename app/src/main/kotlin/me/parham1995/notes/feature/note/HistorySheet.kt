package me.parham1995.notes.feature.note

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.sheetProperties
import me.parham1995.notes.ui.text
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The commits that changed the note, newest first and across renames; one
 * tapped shows the note as it was then, with a way to put it back.
 * [writable] is whether the vault can be written, which a restore needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    vaultId: Long,
    path: String,
    writable: Boolean,
    onWritten: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(vaultId, path) { viewModel.open(vaultId, path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shown = state.shown
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.note_history),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (shown != null) {
                    TextButton(onClick = viewModel::back) { Text(stringResource(R.string.action_back)) }
                }
            }
            if (state.loading || state.reading || state.writing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            }
            state.message?.let {
                Text(
                    it.text(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            state.failure?.let {
                Text(
                    stringResource(R.string.history_failed, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            when {
                shown != null -> {
                    Text(
                        "${shown.subject}\n${shown.author} · ${format.format(
                            shown.date.atZone(ZoneId.systemDefault()),
                        )}" +
                            " · ${shown.commit.take(SHORT)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    if (shown.path != path) {
                        Text(
                            stringResource(R.string.history_was_at, shown.path),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                    state.text?.let { text ->
                        if (writable) {
                            Button(
                                onClick = { viewModel.restore(onWritten) },
                                enabled = !state.writing,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            ) {
                                LucideGlyph("history", size = 18.dp, tint = MaterialTheme.colorScheme.onPrimary)
                                Text(stringResource(R.string.history_restore), Modifier.padding(start = 8.dp))
                            }
                        }
                        SelectionContainer {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(horizontal = 24.dp).horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }

                !state.loading && state.failure == null && state.versions.isEmpty() ->
                    Text(
                        stringResource(R.string.history_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )

                else ->
                    state.versions.forEach { version ->
                        ListItem(
                            onClick = { viewModel.show(version) },
                            leadingContent = { LucideGlyph("git-commit-horizontal", size = 20.dp) },
                            supportingContent = {
                                Text(
                                    "${version.author} · ${format.format(
                                        version.date.atZone(ZoneId.systemDefault()),
                                    )}" +
                                        " · ${version.commit.take(SHORT)}",
                                )
                            },
                        ) { Text(version.subject) }
                    }
            }
        }
    }
}

private const val SHORT = 7
