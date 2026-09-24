package me.parham1995.notes.feature.search

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.icon.VaultIcon
import me.parham1995.notes.ui.inScript

@Composable
fun SearchScreen(
    onOpenNote: (Long) -> Unit,
    onOpenNoteInNewTab: (Long) -> Unit,
    initialQuery: String = "",
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(initialQuery) { viewModel.start(initialQuery) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clear) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.fillMaxSize()) {
            // Jumping straight to a note by name is what most searches want,
            // so it sits above the full-text results rather than below them.
            if (state.quick.isNotEmpty()) {
                item { Label(stringResource(R.string.label_notes)) }
                items(state.quick, key = { "q-${it.note.id}" }) { row ->
                    ResultRow(
                        icon = row.icon,
                        onClick = { onOpenNote(row.note.id) },
                        onLongClick = { onOpenNoteInNewTab(row.note.id) },
                    ) {
                        Text(row.note.title.ifBlank { row.note.name }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            row.note.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }

            if (state.hits.isNotEmpty()) {
                item { Label(stringResource(R.string.search_full_text)) }
                items(state.hits, key = { "h-${it.hit.noteId}" }) { row ->
                    ResultRow(
                        icon = row.icon,
                        onClick = { onOpenNote(row.hit.noteId) },
                        onLongClick = { onOpenNoteInNewTab(row.hit.noteId) },
                        spacing = 2.dp,
                    ) {
                        Text(row.hit.title, style = MaterialTheme.typography.bodyMedium)
                        // A folder searched on its own has no excerpt, only a
                        // place; an empty line where one would be says less.
                        if (row.hit.snippet.isBlank()) {
                            Text(
                                row.hit.path,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            AutoDirection(row.hit.snippet) {
                                Text(
                                    text = row.hit.snippet.highlighted(),
                                    style = MaterialTheme.typography.bodySmall.inScript(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }

            // What the box understands, where there is nothing else to show.
            // Operators nobody knows about are operators nobody uses.
            if (state.query.isBlank()) {
                item {
                    Text(
                        stringResource(R.string.search_syntax),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.query.isNotBlank() && !state.searching && state.quick.isEmpty() && state.hits.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.search_no_match),
                        Modifier.fillMaxWidth().padding(32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * FTS5's `snippet()` marks matches with the delimiters it was given; this turns
 * those into real emphasis rather than leaving brackets in the excerpt.
 */
@Composable
private fun String.highlighted(): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var rest = this@highlighted
        while (true) {
            val open = rest.indexOf('[')
            val close = if (open >= 0) rest.indexOf(']', open) else -1
            if (open < 0 || close < 0) {
                append(rest)
                return@buildAnnotatedString
            }
            append(rest.substring(0, open))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append(rest.substring(open + 1, close))
            }
            rest = rest.substring(close + 1)
        }
    }
}

/** A result line: the note's icon, then whatever the caller puts beside it. */
@Composable
private fun ResultRow(
    icon: IconSpec?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VaultIcon(spec = icon, default = "file-text")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}
