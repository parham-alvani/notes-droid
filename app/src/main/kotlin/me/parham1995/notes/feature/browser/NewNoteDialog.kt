package me.parham1995.notes.feature.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.TemplateFile

/**
 * A title and a template, for a new note in the folder being browsed. The
 * title field has the keyboard as soon as the dialog opens, and Done on it
 * makes the note, so a note from the only template is a title and one key.
 */
@Composable
internal fun NewNoteDialog(
    folder: String,
    templates: List<TemplateFile>,
    onDismiss: () -> Unit,
    onCreate: (TemplateFile, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf(templates.firstOrNull()?.path) }
    val template = templates.firstOrNull { it.path == chosen } ?: templates.firstOrNull()
    val ready = title.isNotBlank() && template != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_new)) },
        text = {
            NewNoteForm(
                folder = folder,
                templates = templates,
                title = title,
                onTitle = { title = it },
                chosen = template,
                onChoose = { chosen = it.path },
                onDone = { if (ready) onCreate(template, title) },
            )
        },
        confirmButton = {
            TextButton(onClick = { template?.let { onCreate(it, title) } }, enabled = ready) {
                Text(stringResource(R.string.template_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The dialog's fields, apart from the dialog: a text field inside a dialog
 * window never settles under Robolectric, so this is the part the tests drive.
 */
@Composable
internal fun NewNoteForm(
    folder: String,
    templates: List<TemplateFile>,
    title: String,
    onTitle: (String) -> Unit,
    chosen: TemplateFile?,
    onChoose: (TemplateFile) -> Unit,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Here rather than around the dialog: this runs once the field is in its
    // window, which is when it can take focus.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.template_in, folder.ifEmpty { stringResource(R.string.browse_root) }),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text(stringResource(R.string.template_title)) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (templates.size > 1) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                templates.forEach { file ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(file == chosen, role = Role.RadioButton) { onChoose(file) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = file == chosen, onClick = null)
                        Text(file.name, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
