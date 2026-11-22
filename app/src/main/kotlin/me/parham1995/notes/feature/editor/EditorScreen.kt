package me.parham1995.notes.feature.editor

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.UiText
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.readingPadding
import me.parham1995.notes.ui.text

/** The banner that says the file changed, for a test to find. */
internal const val EDITOR_CHANGED_TAG = "editor-changed"

/**
 * A note's text, edited (`look.md` in notes-linux's `docs/specs/`, "Android:
 * the editor"). Done and Back save when the text changed, as one commit, and
 * then [onClose] says whether anything was written.
 *
 * [heading] is the number of the heading the note was being read under, so
 * the editor opens there.
 */
@Composable
fun EditorScreen(
    vaultId: Long,
    path: String,
    title: String,
    heading: Int?,
    onClose: (saved: Boolean) -> Unit,
    viewModel: EditorViewModel = hiltViewModel(key = "editor:$vaultId:$path"),
) {
    val session = remember(viewModel, vaultId, path) { viewModel.open(vaultId, path, heading) }
    EditorPage(
        session = session,
        title = title,
        onDone = { viewModel.close(onClose) },
        onReload = viewModel::reload,
    )
}

/** The editor's page over one [session]: the bar, the text, and what it says when the file changed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorPage(
    session: EditorSession,
    title: String,
    onDone: () -> Unit,
    onReload: () -> Unit,
) {
    // Back saves, as Done does.
    BackHandler(onBack = onDone)
    val snackbar = remember { SnackbarHostState() }
    val message = session.message?.text()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            session.message = null
        }
    }
    val missing = stringResource(R.string.note_missing)
    val focus = remember { FocusRequester() }
    // Once the text is there, the caret is in it and the keyboard is up.
    LaunchedEffect(session.opened != null) {
        if (session.opened != null) focus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        LucideGlyph(
                            "arrow-left",
                            size = 20.dp,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onDone, enabled = !session.saving) {
                        Text(stringResource(R.string.editor_done))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().imePadding()) {
            if (session.changed) FileChanged(session, onReload)
            if (session.missing) {
                Text(missing, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // The phone's own line width, as a note is read at.
                        .padding(readingPadding(maxWidth, LocalReading.current.lineWidth)),
                ) {
                    if (session.opened != null) {
                        NoteEditor(
                            value = session.value,
                            onValueChange = { session.value = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            livePreview = LocalReading.current.livePreview,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The file is no longer the text the editor opened, so the save wrote
 * nothing. Two ways on: Reload, which drops what was typed after a confirm,
 * and Copy My Text.
 */
@Composable
private fun FileChanged(
    session: EditorSession,
    onReload: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.editor_copied)
    val label = stringResource(R.string.editor_copy_label)

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag(EDITOR_CHANGED_TAG),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.editor_file_changed), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.editor_reload)) }
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText(label, session.value.text).toClipEntry())
                            session.message = UiText.Raw(copied)
                        }
                    },
                ) { Text(stringResource(R.string.editor_copy_text)) }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.editor_reload_title)) },
            text = { Text(stringResource(R.string.editor_reload_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onReload()
                    },
                ) { Text(stringResource(R.string.editor_reload)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
