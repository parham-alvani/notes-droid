package me.parham1995.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Adds a task: what to do, the file and the section it goes in, and the day
 * it was created, today unless another is picked (the Mac's New Task, whose
 * form has the same Created row).
 *
 * File and section are offered from what the vault already contains rather
 * than typed: this vault keeps one file per context and one heading per
 * project, and a task filed under a heading that does not quite match an
 * existing one is a task nobody finds again. [files] is name to path; the note
 * screen offers just its own note. [sectionsOf] gives a file's section names
 * as `VaultEdits.addUnder` finds them.
 */
@Composable
fun AddTaskDialog(
    files: List<Pair<String, String>>,
    initialPath: String,
    sectionsOf: (String) -> List<String>,
    onDismiss: () -> Unit,
    onAdd: (path: String, section: String, text: String, created: String?) -> Unit,
    initialSection: String = "",
) {
    val form = remember { AddTaskState(initialPath, initialSection) }
    val add = { onAdd(form.path, form.section, form.text, form.created?.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_add)) },
        confirmButton = {
            TextButton(onClick = add, enabled = form.text.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = { AddTaskForm(form, files, sectionsOf, onDone = add) },
    )
}

/** What the New task form holds while it is open. */
class AddTaskState(
    path: String,
    section: String = "",
) {
    var path by mutableStateOf(path)
    var section by mutableStateOf(section)
    var text by mutableStateOf("")

    /** The created day; null leaves ➕ off the line. */
    var created by mutableStateOf<LocalDate?>(LocalDate.now())
}

/** The fields of [AddTaskDialog]; [onDone] is the keyboard's Done with something typed. */
@Composable
internal fun AddTaskForm(
    form: AddTaskState,
    files: List<Pair<String, String>>,
    sectionsOf: (String) -> List<String>,
    onDone: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        CreatedPicker(
            day = form.created ?: LocalDate.now(),
            onDismiss = { picking = false },
            onChoose = {
                form.created = it
                picking = false
            },
        )
    }
    // The task is what the form is for, so the keyboard is up for it as it
    // opens. Here rather than around the dialog: this runs once the field is
    // in its window, which is when it can take focus.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.text,
            onValueChange = { form.text = it },
            label = { Text(stringResource(R.string.tasks_add_text)) },
            keyboardOptions =
                KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            // Done adds it, as the button does, once there is something to add.
            keyboardActions = KeyboardActions(onDone = { if (form.text.isNotBlank()) onDone() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        ChoiceField(
            label = stringResource(R.string.tasks_add_file),
            value = files.firstOrNull { it.second == form.path }?.first ?: form.path,
            // One file is no choice: shown, not offered.
            options = files.takeIf { it.size > 1 }.orEmpty(),
            onChoose = { chosen ->
                form.path = chosen
                // A project heading belongs to a file, so it cannot
                // survive the file changing underneath it.
                form.section = ""
            },
        )
        ChoiceField(
            label = stringResource(R.string.tasks_add_section),
            value = form.section.ifBlank { stringResource(R.string.tasks_add_section_end) },
            options = sectionsOf(form.path).map { it to it },
            onChoose = { form.section = it },
        )
        Column {
            Text(stringResource(R.string.tasks_add_created), style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picking = true }) {
                    LucideGlyph("calendar-plus", size = 16.dp, contentDescription = null)
                    Text(
                        form.created?.toString() ?: stringResource(R.string.tasks_add_created_none),
                        modifier = Modifier.weight(1f, false),
                    )
                }
                if (form.created != null) {
                    TextButton(onClick = { form.created = null }) { Text(stringResource(R.string.action_clear)) }
                }
            }
        }
    }
}

/** The picker of a created day, starting on [day]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatedPicker(
    day: LocalDate,
    onDismiss: () -> Unit,
    onChoose: (LocalDate) -> Unit,
) {
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        // The picker speaks UTC midnight, whatever the
                        // device's zone, so it is read back in UTC too.
                        onChoose(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
                enabled = picker.selectedDateMillis != null,
            ) { Text(stringResource(R.string.tasks_add_created_set)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = picker)
    }
}

/** A read-only field that opens a menu, for choosing from what exists. */
@Composable
private fun ChoiceField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onChoose: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            TextButton(onClick = { open = true }, enabled = options.isNotEmpty()) {
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (options.isNotEmpty()) LucideGlyph("chevron-down", size = 16.dp, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (name, chosen) ->
                    DropdownMenuItem(
                        text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onChoose(chosen)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
