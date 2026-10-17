package me.parham1995.notes.feature.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.text

/**
 * A tab as it looks while sliding in: its title and the note's opening line,
 * set the way the page will set them, so the swipe shows a page and the note
 * then arrives in it rather than replacing it.
 */
@Composable
internal fun TabPreview(
    tab: NoteTab,
    peek: suspend (Long) -> LinkTarget?,
) {
    val target by produceState<LinkTarget?>(initialValue = null, tab.noteId) { value = peek(tab.noteId) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = tab.title.ifBlank { target?.title.orEmpty() },
            style = MaterialTheme.typography.headlineSmall.inScript(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        target?.excerpt?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The open notes, as a row you can move along.
 *
 * Titles rather than numbers, truncated rather than wrapped: the strip has to
 * stay one line high or it is competing with the note for the screen. The one
 * being read is filled in; the rest are outlines.
 */
@Composable
internal fun TabStrip(
    tabs: TabsState,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.tabs.forEachIndexed { index, tab ->
            val selected = index == tabs.active
            FilterChip(
                selected = selected,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        text = tab.title.ifBlank { stringResource(R.string.note_kind) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = TAB_LABEL_WIDTH),
                    )
                },
                trailingIcon = {
                    // Only on the one being read. A close button on every tab
                    // in a row of six is a row of six things to hit by
                    // accident.
                    //
                    // A clickable box rather than an IconButton: the button
                    // insists on a 48dp minimum, which inside a chip is fought
                    // down to something that clips, and its click has to
                    // consume the event or the chip underneath also handles it
                    // and reopens what was just closed.
                    if (selected) {
                        Box(
                            Modifier
                                .size(TAB_CLOSE_SIZE)
                                .clickable { onClose(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            LucideGlyph(
                                "x",
                                size = 14.dp,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    }
                },
            )
        }
    }
}

internal val TAB_LABEL_WIDTH = 140.dp
internal val TAB_CLOSE_SIZE = 26.dp
