package me.parham1995.notes.feature.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.render.FindTarget
import me.parham1995.notes.ui.text

/**
 * Find inside the note that is open.
 *
 * Distinct from search, which answers "which note". 129 of this vault's notes
 * run past 20KB and the longest is 89KB, which is long enough that knowing a
 * word is in there is not the same as being able to reach it.
 *
 * The arrows step between hits rather than listing them: a list of forty
 * matches is another thing to read, and what is wanted is the next one.
 */
@Composable
internal fun FindBar(
    query: String,
    matches: Int,
    /** Which hit the bar is on, from zero; the screen owns it, so the page can mark it. */
    at: Int,
    onQueryChange: (String) -> Unit,
    /** One hit on (+1) or back (-1). */
    onStep: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.note_find)) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Text(
                            text =
                                if (matches == 0) {
                                    stringResource(R.string.note_find_none)
                                } else {
                                    stringResource(R.string.note_find_position, at + 1, matches)
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
            IconButton(
                onClick = { onStep(-1) },
                enabled = matches > 0,
            ) {
                LucideGlyph(
                    "chevron-up",
                    size = 20.dp,
                    contentDescription = stringResource(R.string.note_find_previous),
                )
            }
            IconButton(
                onClick = { onStep(1) },
                enabled = matches > 0,
            ) {
                LucideGlyph("chevron-down", size = 20.dp, contentDescription = stringResource(R.string.note_find_next))
            }
        }
    }
}

/**
 * The hit find starts on: the first one at or below [fromBlock], the block at
 * the top of the screen, the way the Mac reader starts from the first match it
 * can see. Typing a word is asking where it is from here, not from the top of
 * a long note. The first hit when every one is above.
 */
internal fun findStart(
    matchBlocks: List<Int>,
    fromBlock: Int,
): Int = matchBlocks.indexOfFirst { it >= fromBlock }.takeIf { it >= 0 } ?: 0

/** One hit on or back from [at] among [size], round from the last to the first. */
internal fun stepFind(
    at: Int,
    size: Int,
    direction: Int,
): Int = if (size == 0) 0 else ((at + direction) % size + size) % size

/**
 * What the page marks for hit [at]: its block, and which of that block's hits
 * it is. Null with nothing to mark.
 */
internal fun findTarget(
    query: String,
    matchBlocks: List<Int>,
    at: Int,
): FindTarget? {
    if (query.isBlank()) return null
    val block = matchBlocks.getOrNull(at) ?: return FindTarget(query, block = -1, ordinal = -1)
    val ordinal = (0 until at).count { matchBlocks[it] == block }
    return FindTarget(query, block, ordinal)
}

/**
 * Whether a hit's block needs bringing on screen, from where its top is drawn
 * ([top], null when it is not drawn at all). A block whose top is in the
 * lower third is as good as off screen: the hit is likely below the fold.
 */
internal fun findNeedsScroll(
    top: Int?,
    viewportStart: Int,
    viewportEnd: Int,
): Boolean {
    top ?: return true
    return top > viewportStart + (viewportEnd - viewportStart) * 2 / 3
}
