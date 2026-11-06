package me.parham1995.notes.feature.note

import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.markdown.FrontMatterForm
import me.parham1995.notes.markdown.Property
import me.parham1995.notes.markdown.PropertyEdit
import me.parham1995.notes.markdown.PropertyNames
import me.parham1995.notes.markdown.PropertyType
import me.parham1995.notes.markdown.PropertyValue
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.sheetProperties
import me.parham1995.notes.ui.text
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The note's front matter as a form, as Obsidian's Properties view edits it:
 * a row per property with a control for its type, a menu to rename or remove
 * it, and Add property. Front matter the form cannot show is said so, with
 * the reason, and shown as it is written. `properties.md` in notes-linux's
 * `docs/specs/`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesSheet(
    noteId: Long,
    vaultId: Long,
    path: String,
    onWritten: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: PropertiesViewModel = hiltViewModel(),
) {
    LaunchedEffect(noteId) { viewModel.open(noteId, vaultId, path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val edit = { change: PropertyEdit -> viewModel.edit(change, onWritten = onWritten) }

    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.note_properties), style = MaterialTheme.typography.titleMedium)
            if (state.loading || state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { message ->
                Text(
                    message.text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (val form = state.form) {
                null -> Unit
                is FrontMatterForm.Source -> {
                    Text(
                        stringResource(R.string.properties_raw, form.reason),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        state.source,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
                else -> {
                    val sheet = form.sheet ?: return@Column
                    if (sheet.properties.isEmpty()) {
                        Text(
                            stringResource(R.string.properties_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    sheet.properties.forEach { property ->
                        PropertyRow(
                            property = property,
                            type = state.typeOf(property.key, property.value),
                            enabled = !state.writing && !state.waiting,
                            onEdit = edit,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.loadNames()
                            adding = true
                        },
                        enabled = !state.writing && !state.waiting,
                    ) {
                        LucideGlyph("plus", size = 18.dp)
                        Text(stringResource(R.string.properties_add), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    if (adding) {
        AddPropertyDialog(
            names = state.names,
            taken =
                state.form
                    ?.sheet
                    ?.properties
                    ?.map { it.key }
                    .orEmpty()
                    .toSet(),
            onAdd = { name, type ->
                adding = false
                val value = if (type == PropertyType.CHECKBOX) PropertyValue.Text("false") else PropertyValue.Empty
                viewModel.edit(PropertyEdit.Add(name, value), type, onWritten)
            },
            onDismiss = { adding = false },
        )
    }
}

/** One property: its name and menu, and the control for its type. */
@Composable
internal fun PropertyRow(
    property: Property,
    type: PropertyType,
    enabled: Boolean,
    onEdit: (PropertyEdit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                property.key,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(type.title()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    LucideGlyph(
                        "ellipsis-vertical",
                        size = 18.dp,
                        contentDescription = stringResource(R.string.properties_menu, property.key),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.properties_rename)) },
                        onClick = {
                            menu = false
                            renaming = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.properties_remove)) },
                        onClick = {
                            menu = false
                            onEdit(PropertyEdit.Remove(property.key))
                        },
                    )
                }
            }
        }
        PropertyControl(property, type, enabled) { value -> onEdit(PropertyEdit.Set(property.key, value)) }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.properties_rename),
            initial = property.key,
            onDone = { name ->
                renaming = false
                onEdit(PropertyEdit.Rename(property.key, name))
            },
            onDismiss = { renaming = false },
        )
    }
}

/** The control for [type]: a box, a date with a calendar, chips, or a field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertyControl(
    property: Property,
    type: PropertyType,
    enabled: Boolean,
    onSet: (PropertyValue) -> Unit,
) {
    val value = property.value
    val text = (value as? PropertyValue.Text)?.text.orEmpty()
    when {
        type == PropertyType.CHECKBOX ->
            Checkbox(
                checked = text.equals("true", ignoreCase = true),
                onCheckedChange = { onSet(PropertyValue.Text(if (it) "true" else "false")) },
                enabled = enabled,
            )

        type == PropertyType.DATE || type == PropertyType.DATETIME -> DateControl(text, type, enabled, onSet)

        type.isList || value is PropertyValue.Items ->
            ListControl(
                value.items,
                enabled,
            ) { onSet(PropertyValue.Items(it)) }

        else ->
            TextControl(
                text,
                numeric = type == PropertyType.NUMBER,
                enabled = enabled,
            ) { onSet(PropertyValue.Text(it)) }
    }
}

/** Written on Done, or when the field loses the focus -- and only when it changed. */
@Composable
private fun TextControl(
    text: String,
    numeric: Boolean,
    enabled: Boolean,
    onSet: (String) -> Unit,
) {
    var field by remember(text) { mutableStateOf(text) }
    var focused by remember { mutableStateOf(false) }
    val commit = { if (field != text) onSet(field) }
    OutlinedTextField(
        value = field,
        onValueChange = { field = it },
        enabled = enabled,
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier =
            Modifier.fillMaxWidth().onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            },
    )
}

/** The items as chips, each with a way off, and a field that adds one. */
@Composable
private fun ListControl(
    items: List<String>,
    enabled: Boolean,
    onSet: (List<String>) -> Unit,
) {
    var field by remember(items) { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            InputChip(
                selected = false,
                enabled = enabled,
                onClick = { onSet(items.filterIndexed { at, _ -> at != index }) },
                label = { Text(item) },
                trailingIcon = {
                    LucideGlyph(
                        "x",
                        size = 14.dp,
                        contentDescription = stringResource(R.string.properties_remove_item, item),
                    )
                },
            )
        }
    }
    OutlinedTextField(
        value = field,
        onValueChange = { field = it },
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.properties_add_item)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions =
            KeyboardActions(onDone = {
                val item = field.trim()
                if (item.isNotEmpty()) onSet(items + item)
            }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The date as Obsidian writes it, and a calendar to pick another; a date and time asks the time after. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateControl(
    text: String,
    type: PropertyType,
    enabled: Boolean,
    onSet: (PropertyValue) -> Unit,
) {
    val context = LocalContext.current
    val current = PropertyType.date(text)
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, enabled = enabled) {
        LucideGlyph("calendar", size = 18.dp)
        Text(text.ifEmpty { stringResource(R.string.properties_pick_date) }, Modifier.padding(start = 8.dp))
    }
    if (!picking) return
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis =
                (current?.toLocalDate() ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = { picking = false },
        confirmButton = {
            TextButton(onClick = {
                picking = false
                val day =
                    picker.selectedDateMillis?.let { LocalDate.ofEpochDay(it / MILLIS_PER_DAY) } ?: return@TextButton
                if (type == PropertyType.DATE) {
                    onSet(PropertyValue.Text(PropertyType.format(day.atStartOfDay(), time = false)))
                } else {
                    val time = current?.toLocalTime() ?: LocalTime.of(DEFAULT_HOUR, 0)
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            onSet(
                                PropertyValue.Text(
                                    PropertyType.format(LocalDateTime.of(day, LocalTime.of(hour, minute)), time = true),
                                ),
                            )
                        },
                        time.hour,
                        time.minute,
                        true,
                    ).show()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state = picker) }
}

/**
 * A new property's name, offered from the vault's own names that match what
 * is typed (the most used first), and its type.
 */
@Composable
private fun AddPropertyDialog(
    names: List<String>,
    taken: Set<String>,
    onAdd: (String, PropertyType) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(PropertyType.TEXT) }
    val offered = PropertyNames.complete(name, names.filter { it !in taken }, SUGGESTIONS)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.properties_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.properties_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    offered.forEach { suggestion ->
                        SuggestionChip(onClick = { name = suggestion }, label = { Text(suggestion) })
                    }
                }
                Text(stringResource(R.string.properties_type), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PropertyType.entries.forEach { choice ->
                        FilterChip(
                            selected = type == choice,
                            onClick = { type = choice },
                            label = { Text(stringResource(choice.title())) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name.trim(), type) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.properties_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The name the form gives a type, as Obsidian's menu does. */
private fun PropertyType.title(): Int =
    when (this) {
        PropertyType.TEXT -> R.string.property_type_text
        PropertyType.MULTITEXT -> R.string.property_type_list
        PropertyType.NUMBER -> R.string.property_type_number
        PropertyType.CHECKBOX -> R.string.property_type_checkbox
        PropertyType.DATE -> R.string.property_type_date
        PropertyType.DATETIME -> R.string.property_type_datetime
        PropertyType.ALIASES -> R.string.property_type_aliases
        PropertyType.TAGS -> R.string.property_type_tags
    }

private const val SUGGESTIONS = 8
private const val DEFAULT_HOUR = 9
private const val MILLIS_PER_DAY = 86_400_000L
