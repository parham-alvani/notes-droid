package me.parham1995.notes.feature.editor

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.markdown.EditorCommands
import me.parham1995.notes.markdown.EditorEdit
import me.parham1995.notes.markdown.EditorTasks
import me.parham1995.notes.markdown.TaskStatuses
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.LocalTaskStatuses
import me.parham1995.notes.ui.UiText
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.readingPadding
import me.parham1995.notes.ui.sheetProperties
import me.parham1995.notes.ui.text
import java.time.LocalDate

/** The banner that says the file changed, for a test to find. */
internal const val EDITOR_CHANGED_TAG = "editor-changed"

/** The format bar, for a test to find. */
internal const val FORMAT_BAR_TAG = "format-bar"

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

/**
 * The editor's page over one [session]: the bar, the text, the format bar
 * over the keyboard, and what it says when the file changed. [today] is the
 * day a tick is dated and a due date is late against.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorPage(
    session: EditorSession,
    title: String,
    onDone: () -> Unit,
    onReload: () -> Unit,
    today: LocalDate = rememberToday(),
) {
    val reading = LocalReading.current
    val statuses = LocalTaskStatuses.current
    val haptics = LocalHapticFeedback.current
    // The place of the task whose status sheet is open.
    var statusOf by remember { mutableStateOf<Int?>(null) }
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
                    IconButton(onClick = session::undo, enabled = session.canUndo) {
                        LucideGlyph("undo-2", size = 20.dp, contentDescription = stringResource(R.string.editor_undo))
                    }
                    IconButton(onClick = session::redo, enabled = session.canRedo) {
                        LucideGlyph("redo-2", size = 20.dp, contentDescription = stringResource(R.string.editor_redo))
                    }
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
                            onValueChange = session::change,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            livePreview = reading.livePreview,
                            today = today,
                            statuses = statuses,
                            // A tap on a box is the task toggle: the text changes, and the save is the editor's.
                            onTask = { at ->
                                val value = session.value
                                val selection = value.selection
                                val edit =
                                    EditorCommands.toggleTask(
                                        value.text,
                                        at,
                                        selection.start,
                                        selection.end,
                                        today.toString(),
                                        statuses,
                                    )
                                if (edit != null) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                session.run(edit)
                            },
                            onTaskMenu = { statusOf = it },
                        )
                    }
                }
            }
            // Over the keyboard, as wide as the screen: the system's own bar is over the selection.
            if (reading.formatBar && session.selectionInLine) FormatBar(session)
        }
    }
    statusOf?.let { at ->
        StatusSheet(
            current = EditorCommands.statusAt(session.value.text, at),
            statuses = statuses,
            onDismiss = { statusOf = null },
            onPick = { status ->
                statusOf = null
                val value = session.value
                val selection = value.selection
                session.run(
                    EditorCommands.setStatus(
                        value.text,
                        at,
                        selection.start,
                        selection.end,
                        status,
                        today.toString(),
                        statuses,
                    ),
                )
            },
        )
    }
}

/** A button of the format bar: its icon, what it is called, and what it does to the selection. */
internal enum class FormatButton(
    val icon: String,
    val label: Int,
    val apply: (text: String, start: Int, end: Int) -> EditorEdit,
) {
    BOLD("bold", R.string.format_bold, {
        text,
        start,
        end,
        ->
        EditorCommands.wrap(text, start, end, EditorCommands.BOLD)
    }),
    ITALIC("italic", R.string.format_italic, { text, start, end ->
        EditorCommands.wrap(text, start, end, EditorCommands.ITALIC)
    }),
    HIGHLIGHT("highlighter", R.string.format_highlight, { text, start, end ->
        EditorCommands.wrap(text, start, end, EditorCommands.HIGHLIGHT)
    }),
    CODE("code", R.string.format_code, {
        text,
        start,
        end,
        ->
        EditorCommands.wrap(text, start, end, EditorCommands.CODE)
    }),
    STRIKE("strikethrough", R.string.format_strike, { text, start, end ->
        EditorCommands.wrap(text, start, end, EditorCommands.STRIKE)
    }),
    LINK("link", R.string.format_link, EditorCommands::link),
}

/**
 * The format bar: bold, italic, highlight, code, strike and a link to a
 * note, for a selection inside one line. A button is one undo step and keeps
 * the selection. It takes no focus, so the keys stay the text's.
 */
@Composable
private fun FormatBar(session: EditorSession) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag(FORMAT_BAR_TAG),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            for (button in FormatButton.entries) {
                IconButton(
                    onClick = {
                        val value = session.value
                        session.run(button.apply(value.text, value.selection.min, value.selection.max))
                    },
                    modifier = Modifier.size(FORMAT_BUTTON),
                ) {
                    LucideGlyph(
                        button.icon,
                        size = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurface,
                        contentDescription = stringResource(button.label),
                    )
                }
            }
        }
    }
}

private val FORMAT_BUTTON = 48.dp

/**
 * The statuses a task can have, the vault's own, each with the box the
 * editor draws for it. A long press on a task's box opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSheet(
    current: Char?,
    statuses: TaskStatuses,
    onDismiss: () -> Unit,
    onPick: (Char) -> Unit,
) {
    val look = rememberEditorLook()
    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.editor_status),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            for (status in statuses.all) {
                val symbol = EditorTasks.drawing(status.symbol, statuses)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(status.symbol) }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LucideGlyph(symbol.icon, size = 20.dp, tint = look.taskColor(symbol.tint))
                    Text(status.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (status.symbol == current) {
                        LucideGlyph("check", size = 18.dp, tint = MaterialTheme.colorScheme.primary)
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
