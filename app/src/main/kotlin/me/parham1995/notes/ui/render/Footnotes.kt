package me.parham1995.notes.ui.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.markdown.FootnoteEntry
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.ui.inScript

/**
 * The footnotes at the end of a note, numbered as the text cites them.
 *
 * Obsidian draws them under a rule at the bottom; so does this, and a number
 * in the text opens the one it names in a sheet rather than scrolling away
 * from the sentence being read.
 */
@Composable
internal fun FootnotesView(
    block: MdBlock.Footnotes,
    actions: RenderActions,
    brokenLinks: Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text = stringResource(R.string.note_footnotes),
            style = MaterialTheme.typography.labelLarge.inScript(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        block.entries.forEach { entry -> FootnoteRow(entry, actions, brokenLinks) }
    }
}

@Composable
private fun FootnoteRow(
    entry: FootnoteEntry,
    actions: RenderActions,
    brokenLinks: Set<String>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "${entry.number}.",
            modifier = Modifier.widthIn(min = NUMBER_WIDTH),
            style = MaterialTheme.typography.bodyMedium.inScript(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entry.blocks.forEach { MdBlockView(it, actions, brokenLinks) }
        }
    }
}

/**
 * One footnote, opened from its number in the text.
 *
 * Drawn with the note's own actions, so a link inside the footnote goes where
 * a link in the note would.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootnoteSheet(
    entry: FootnoteEntry,
    actions: RenderActions,
    brokenLinks: Set<String>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.note_footnote, entry.number),
                style = MaterialTheme.typography.labelLarge.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.blocks.forEach { MdBlockView(it, actions, brokenLinks) }
        }
    }
}

private val NUMBER_WIDTH = 20.dp
