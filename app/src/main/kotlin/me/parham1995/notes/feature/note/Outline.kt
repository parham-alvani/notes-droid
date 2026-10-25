package me.parham1995.notes.feature.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.ui.sheetProperties

/**
 * The note's headings, as a sheet: tap one to go there.
 *
 * The section being read is marked and the list opens on it, so in a note
 * with forty headings the outline answers "where am I" before "where to".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutlineSheet(
    headings: List<HeadingEntity>,
    current: Long?,
    onDismiss: () -> Unit,
    onJump: (HeadingEntity) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        OutlineList(headings, current, onJump)
    }
}

/** The sheet's list, apart from the sheet, so a test can tap it. */
@Composable
internal fun OutlineList(
    headings: List<HeadingEntity>,
    current: Long?,
    onJump: (HeadingEntity) -> Unit,
) {
    if (headings.isEmpty()) {
        Text(
            stringResource(R.string.note_no_headings),
            Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val at = headings.indexOfFirst { it.id == current }
    // A couple of headings of context above the current one.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (at - CONTEXT_ABOVE).coerceAtLeast(0))
    LazyColumn(state = list) {
        items(headings, key = { it.id }) { heading ->
            val here = heading.id == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = here, onClick = { onJump(heading) })
                    // Indent by level so the outline reads as a structure
                    // rather than a flat list.
                    .padding(start = (12 + (heading.level - 1) * 12).dp, end = 16.dp)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A bar beside the section being read, in the accent, the
                // way a sidebar marks the page it is on.
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .width(3.dp)
                        .height(18.dp)
                        .background(
                            if (here) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(2.dp),
                        ),
                )
                Text(
                    text = heading.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (here) FontWeight.SemiBold else null,
                )
            }
        }
    }
}

private const val CONTEXT_ABOVE = 2

/**
 * The heading whose section holds the block at [block]: the last one at or
 * above it. Null above the first heading, where no section has begun.
 */
internal fun currentHeading(
    headings: List<HeadingEntity>,
    block: Int,
): Long? =
    headings
        .sortedBy { it.ordinal }
        .lastOrNull { it.blockIndex <= block }
        ?.id
