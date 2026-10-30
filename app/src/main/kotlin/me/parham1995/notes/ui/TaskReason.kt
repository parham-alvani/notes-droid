package me.parham1995.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.markdown.TaskStatuses
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.render.FindMarks
import me.parham1995.notes.ui.render.findColors
import me.parham1995.notes.ui.render.findReport
import me.parham1995.notes.ui.render.withFindMarks

/**
 * The open vault's task statuses, from its Tasks plugin settings, for what
 * draws a task without being handed them: the reader's checkboxes.
 */
val LocalTaskStatuses = staticCompositionLocalOf { TaskStatuses.DEFAULT }

/**
 * A task's reason after its text, quieter than it: `💬 no budget left`. The
 * same mark Daftar for Linux and the Mac put there. In a note, find in note
 * marks its hits ([marks]), all of it shown while one is the current hit.
 */
@Composable
fun TaskReasonLine(
    reason: String,
    modifier: Modifier = Modifier,
    size: Dp = 13.dp,
    maxLines: Int = 2,
    marks: FindMarks? = null,
) {
    val colors = findColors()
    val text = remember(reason, marks, colors) { AnnotatedString(reason).withFindMarks(marks, colors) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val holdsCurrent = marks != null && (marks.everyCurrent || marks.current != null)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LucideGlyph(
            "message-circle",
            size = size,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
            contentDescription = stringResource(R.string.task_reason),
        )
        Text(
            text,
            modifier = Modifier.findReport(text, marks, layout),
            style = MaterialTheme.typography.bodySmall.inScript(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (holdsCurrent) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
        )
    }
}

/** A status that is not one of the four every vault has, by its name: "In Review". */
@Composable
fun TaskStatusBadge(
    name: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * Asks for a task's reason: before it is cancelled, when [cancelling], or
 * to write, change or clear one for whatever status it has. Prefilled with
 * [current] and holding the keyboard; Done on it is the confirm button. An
 * empty field cancels without a reason, or clears one; dismissing changes
 * nothing.
 */
@Composable
fun TaskReasonDialog(
    current: String?,
    cancelling: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (cancelling) R.string.task_reason_cancel_title else R.string.task_reason)) },
        text = {
            // Here rather than around the dialog: this runs once the field is
            // in its window, which is when it can take focus.
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.task_reason)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(reason) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(if (cancelling) R.string.task_reason_cancel_confirm else R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (cancelling) R.string.task_reason_cancel_back else R.string.action_back))
            }
        },
    )
}
