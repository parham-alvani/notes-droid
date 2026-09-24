package me.parham1995.notes.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.ItemRow

/**
 * The notes that changed upstream since they were last opened, as a section of
 * the browser's root.
 *
 * Nothing at all when there are none -- a heading over an empty list is a row
 * of chrome that says nothing, and on most opens there will be none. Opening
 * one marks it read, which is what takes it off the list again.
 */
internal fun LazyListScope.updatedSinceRead(
    rows: List<RecentRow>,
    onOpen: (Long) -> Unit,
    onOpenInNewTab: (Long) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "updated-label") { SectionLabel(stringResource(R.string.browse_updated)) }
    items(rows, key = { "updated-${it.note.id}" }) { row ->
        ItemRow(
            title = row.note.title.ifBlank { row.note.name },
            // The folder, because the laptop's changes cluster -- a day's
            // journal and the project it mentions -- and where a note lives
            // says which is which faster than its name.
            subtitle = row.note.parent,
            icon = row.icon,
            defaultIcon = "file-text",
            iconDescription = stringResource(R.string.note_kind),
            onClick = { onOpen(row.note.id) },
            onLongClick = { onOpenInNewTab(row.note.id) },
        )
    }
    item(key = "updated-divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
}

/**
 * A note that changed since it was last read, marked where it is listed
 * elsewhere -- Recently opened, and the folder it sits in.
 *
 * A dot rather than a label: it is a hint beside the name, not a second line,
 * and it goes the moment the note is opened.
 */
@Composable
internal fun UpdatedDot() {
    val description = stringResource(R.string.browse_updated_marker)
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .size(8.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = description },
    )
}
