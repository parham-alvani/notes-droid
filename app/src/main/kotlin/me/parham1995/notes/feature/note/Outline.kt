package me.parham1995.notes.feature.note

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.markdown.OutlineTree
import me.parham1995.notes.markdown.ReadingTime
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.sheetProperties

/**
 * The note's headings, as a sheet: tap one to go there, close one to see
 * less of the outline, or sort the headings under one.
 *
 * The section being read is marked and the list opens on it, so in a note
 * with forty headings the outline answers "where am I" before "where to".
 * Above it, how long the note is and how long it takes to read, as the bar
 * above a note on the Mac says it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutlineSheet(
    headings: List<HeadingEntity>,
    current: Long?,
    onDismiss: () -> Unit,
    onJump: (HeadingEntity) -> Unit,
    reading: ReadingTime? = null,
    closed: Set<String> = emptySet(),
    onToggle: ((index: Int, all: Boolean) -> Unit)? = null,
    onSort: ((index: Int) -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        reading?.let { ReadingLine(it) }
        OutlineList(headings, current, onJump, closed, onToggle, onSort)
    }
}

/** "1,240 words · 6 min", for a note long enough to say it of. */
@Composable
internal fun ReadingLine(reading: ReadingTime) {
    Text(
        text =
            pluralStringResource(R.plurals.note_words, reading.words, reading.words) + " · " +
                pluralStringResource(R.plurals.note_minutes, reading.minutes, reading.minutes),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The sheet's list, apart from the sheet, so a test can tap it.
 *
 * A heading with headings under it has an arrow that closes it in the
 * outline, and only there: the note is not folded. A long press offers the
 * same for every heading of its level, and sorts the headings right under it
 * by name. Without [onToggle] and [onSort], the list is as it was.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OutlineList(
    headings: List<HeadingEntity>,
    current: Long?,
    onJump: (HeadingEntity) -> Unit,
    closed: Set<String> = emptySet(),
    onToggle: ((index: Int, all: Boolean) -> Unit)? = null,
    onSort: ((index: Int) -> Unit)? = null,
) {
    if (headings.isEmpty()) {
        Text(
            stringResource(R.string.note_no_headings),
            Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val ordered = remember(headings) { headings.sortedBy { it.ordinal } }
    val levels = remember(ordered) { ordered.map { it.level } }
    val shown =
        remember(ordered, closed) {
            OutlineTree.visible(levels) { OutlineTree.key(ordered[it].level, ordered[it].text) in closed }
        }
    // The section in a closed heading marks that heading.
    val here = ordered.indexOfFirst { it.id == current }.takeIf { it >= 0 }?.let { OutlineTree.shown(it, shown) }
    // A couple of headings of context above the current one.
    val list =
        rememberLazyListState(
            initialFirstVisibleItemIndex = ((here?.let(shown::indexOf) ?: 0) - CONTEXT_ABOVE).coerceAtLeast(0),
        )
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf<Int?>(null) }
    LazyColumn(state = list) {
        items(shown, key = { ordered[it].id }) { index ->
            val heading = ordered[index]
            val selected = index == here
            val parent = onToggle != null && OutlineTree.hasChildren(levels, index)
            val shut = parent && OutlineTree.key(heading.level, heading.text) in closed
            val sortable = onSort != null && OutlineTree.children(levels, index).size > 1
            val closeLabel = stringResource(if (shut) R.string.outline_open else R.string.outline_close)
            val levelLabel = stringResource(if (shut) R.string.outline_open_level else R.string.outline_close_level)
            val sortLabel = stringResource(R.string.outline_sort)
            val state = stringResource(if (shut) R.string.state_collapsed else R.string.state_expanded)
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onJump(heading) },
                            onLongClick =
                                if (parent || sortable) {
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menu = index
                                    }
                                } else {
                                    null
                                },
                        ).semantics {
                            this.selected = selected
                            if (parent) stateDescription = state
                            customActions =
                                listOfNotNull(
                                    if (parent) {
                                        CustomAccessibilityAction(closeLabel) {
                                            onToggle(index, false)
                                            true
                                        }
                                    } else {
                                        null
                                    },
                                    if (parent) {
                                        CustomAccessibilityAction(levelLabel) {
                                            onToggle(index, true)
                                            true
                                        }
                                    } else {
                                        null
                                    },
                                    if (sortable) {
                                        CustomAccessibilityAction(sortLabel) {
                                            onSort(index)
                                            true
                                        }
                                    } else {
                                        null
                                    },
                                )
                        }
                        // Indent by level so the outline reads as a structure
                        // rather than a flat list.
                        .padding(start = (4 + (heading.level - 1) * 12).dp, end = 16.dp)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The arrow, or its room, so the names line up by level.
                    Box(Modifier.size(ARROW_TARGET), contentAlignment = Alignment.Center) {
                        if (parent) {
                            Box(
                                Modifier
                                    .size(ARROW_TARGET)
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        onToggle(index, false)
                                    }.clearAndSetSemantics {},
                                contentAlignment = Alignment.Center,
                            ) {
                                // A closed heading points along the reading
                                // direction, as a folded section does.
                                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                                LucideGlyph(
                                    if (!shut) {
                                        "chevron-down"
                                    } else if (rtl) {
                                        "chevron-left"
                                    } else {
                                        "chevron-right"
                                    },
                                    size = ARROW,
                                    tint =
                                        if (shut) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                    // A bar beside the section being read, in the accent, the
                    // way a sidebar marks the page it is on.
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .width(3.dp)
                            .height(18.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    Text(
                        text = heading.text,
                        modifier = Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.SemiBold else null,
                    )
                }
                DropdownMenu(expanded = menu == index, onDismissRequest = { menu = null }) {
                    if (parent) {
                        DropdownMenuItem(text = { Text(closeLabel) }, onClick = {
                            menu = null
                            onToggle(index, false)
                        })
                        DropdownMenuItem(text = { Text(levelLabel) }, onClick = {
                            menu = null
                            onToggle(index, true)
                        })
                    }
                    if (sortable) {
                        DropdownMenuItem(text = { Text(sortLabel) }, onClick = {
                            menu = null
                            onSort(index)
                        })
                    }
                }
            }
        }
    }
}

private val ARROW = 18.dp
private val ARROW_TARGET = 36.dp

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
