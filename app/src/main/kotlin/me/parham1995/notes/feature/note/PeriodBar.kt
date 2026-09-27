package me.parham1995.notes.feature.note

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.PeriodNeighbours
import me.parham1995.notes.data.database.NoteRef
import me.parham1995.notes.ui.icon.LucideGlyph

/**
 * The way along a journal: the daily -- or weekly, or monthly -- note before
 * this one and the one after, named, so a gap of three weeks is visible before
 * it is jumped.
 *
 * A strip of its own under the title rather than two more icons in the top
 * bar, which already holds five and has the title to make room for. Drawn only
 * on a note that is one of its vault's daily notes; [neighbours] is null on
 * every other. An end of the series is a disabled arrow, not a missing one, so
 * the other does not move.
 */
@Composable
fun PeriodBar(
    neighbours: PeriodNeighbours?,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    neighbours ?: return
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Step(neighbours.previous, forward = false, onOpen)
            Step(neighbours.next, forward = true, onOpen)
        }
        YearsAgo(neighbours.yearsAgo, onOpen)
    }
}

/**
 * This week -- or day, or month -- in the years before: a journal read back
 * at the same time of year. Only the years that have a note are offered.
 */
@Composable
private fun YearsAgo(
    earlier: List<Pair<Int, NoteRef>>,
    onOpen: (Long) -> Unit,
) {
    if (earlier.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideGlyph("history", size = 16.dp)
        earlier.forEach { (years, note) ->
            AssistChip(
                onClick = { onOpen(note.id) },
                label = { Text(pluralStringResource(R.plurals.period_years_ago, years, years)) },
            )
        }
    }
}

@Composable
private fun Step(
    target: NoteRef?,
    forward: Boolean,
    onOpen: (Long) -> Unit,
) {
    // Mirrored with the layout: in a right-to-left locale earlier is on the right.
    val arrow =
        if (forward) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft
    if (target == null) {
        IconButton(onClick = {}, enabled = false) {
            Icon(
                arrow,
                contentDescription =
                    stringResource(
                        if (forward) R.string.period_no_next else R.string.period_no_previous,
                    ),
            )
        }
        return
    }
    val name = target.path.substringAfterLast('/').removeSuffix(".md")
    val said = stringResource(if (forward) R.string.period_next else R.string.period_previous, name)
    TextButton(
        onClick = { onOpen(target.id) },
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.semantics { contentDescription = said },
    ) {
        if (!forward) Icon(arrow, contentDescription = null)
        Text(name)
        if (forward) Icon(arrow, contentDescription = null)
    }
}
