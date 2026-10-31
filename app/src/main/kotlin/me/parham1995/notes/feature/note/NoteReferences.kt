package me.parham1995.notes.feature.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.ItemRow
import me.parham1995.notes.ui.VaultRowItem
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.render.Attachments
import me.parham1995.notes.ui.text

/**
 * What the folder actually holds.
 *
 * A subfolder opens its own landing page when it has one, which is the same
 * rule the browser follows. When it has none there is no note to show, so it
 * hands off to the browser at that path rather than pretending otherwise.
 */
@Composable
internal fun FolderContents(
    rows: List<VaultRowItem>,
    onOpenNote: (Long) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenGraph: (Long) -> Unit = {},
    /** A file that is not a note, by its path: a base, a PDF, a picture. */
    onOpenFile: (String) -> Unit = {},
) {
    if (rows.isEmpty()) {
        Text(
            stringResource(R.string.note_folder_empty),
            Modifier.fillMaxWidth().padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.item.path }) { row ->
            val item = row.item
            ItemRow(
                title = item.name,
                icon = row.icon,
                logo = row.logo,
                defaultIcon =
                    when {
                        item.isFolder -> "folder"
                        item.isAttachment -> Attachments.iconOf(item.path)
                        else -> "file-text"
                    },
                iconDescription =
                    when {
                        item.isFolder -> "Folder"
                        item.isAttachment -> stringResource(R.string.kind_file)
                        else -> stringResource(R.string.note_kind)
                    },
                underline = item.isFolder && item.noteId != null,
                onClick = {
                    val note = item.noteId
                    when {
                        note != null -> onOpenNote(note)
                        item.isAttachment -> onOpenFile(item.path)
                        else -> onOpenFolder(item.path)
                    }
                },
            )
        }
    }
}

@Composable
internal fun SheetLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One reference to this note: a link that points here, or a mention that does not. */
@Composable
internal fun ReferenceRow(
    title: String,
    context: AnnotatedString,
    path: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        AutoDirection(context.text) {
            Text(
                text = context,
                style = MaterialTheme.typography.bodySmall.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = path,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    HorizontalDivider()
}

/**
 * The excerpt FTS5 hands back, with its markers turned into emphasis.
 *
 * `snippet()` wraps the matched terms in the delimiters it was given rather
 * than returning positions, so this is the only way to know where the match
 * was without searching the excerpt again.
 */
internal fun String.highlighted(strong: Color): AnnotatedString =
    buildAnnotatedString {
        var rest = this@highlighted
        while (true) {
            val open = rest.indexOf('[')
            val close = rest.indexOf(']', startIndex = open + 1)
            if (open < 0 || close < 0) break
            append(rest.substring(0, open))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) {
                append(rest.substring(open + 1, close))
            }
            rest = rest.substring(close + 1)
        }
        append(rest)
    }
