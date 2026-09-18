package me.parham1995.notes.ui.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.StylusSpotlight

/**
 * A whole note.
 *
 * The list is the renderer's central decision: one `LazyColumn` item per block,
 * each with its own `AnnotatedString`, so opening the largest note in a vault
 * composes only the handful of blocks on screen. Building one string for the
 * document would stall the frame that did it.
 */
@Composable
fun MarkdownDocument(
    blocks: List<MdBlock>,
    actions: RenderActions,
    modifier: Modifier = Modifier,
    brokenLinks: Set<String> = emptySet(),
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val reading = LocalReading.current
    // Outside the SelectionContainer, so watching the pen cannot interfere
    // with the gesture that selects text.
    StylusSpotlight(
        enabled = reading.stylusSpotlight,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.primary,
    ) {
        // Selection spans the document rather than each block, so copying a
        // paragraph and the heading above it works.
        SelectionContainer {
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = blocks, key = { it.id }) { block ->
                    MdBlockView(block, actions, brokenLinks)
                }
            }
        }
    }
}
