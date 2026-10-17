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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph
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
    matchBlocks: List<Int>,
    onQueryChange: (String) -> Unit,
    onJump: (Int) -> Unit,
) {
    var at by remember(matchBlocks) { mutableIntStateOf(0) }

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
                onClick = {
                    if (matchBlocks.isEmpty()) return@IconButton
                    at = (at - 1 + matchBlocks.size) % matchBlocks.size
                    onJump(matchBlocks[at])
                },
                enabled = matches > 0,
            ) {
                LucideGlyph(
                    "chevron-up",
                    size = 20.dp,
                    contentDescription = stringResource(R.string.note_find_previous),
                )
            }
            IconButton(
                onClick = {
                    if (matchBlocks.isEmpty()) return@IconButton
                    at = (at + 1) % matchBlocks.size
                    onJump(matchBlocks[at])
                },
                enabled = matches > 0,
            ) {
                LucideGlyph("chevron-down", size = 20.dp, contentDescription = stringResource(R.string.note_find_next))
            }
        }
    }
}
