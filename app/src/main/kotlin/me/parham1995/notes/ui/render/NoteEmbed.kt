package me.parham1995.notes.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.markdown.MarkdownLinks
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript

/**
 * Another note, or one section of it, as an embed draws it.
 *
 * [linkTargets] and [brokenLinks] are the embedded note's own: a link inside
 * it means what it means in the note it was written in.
 */
class Transcluded(
    val noteId: Long,
    val title: String,
    val blocks: List<MdBlock>,
    val linkTargets: Map<String, Long>,
    val brokenLinks: Set<String>,
)

/**
 * How many embeds deep this block is being drawn.
 *
 * One level is drawn in place and anything deeper is a link. A note that
 * embeds itself, or two that embed each other, would otherwise draw forever.
 */
private val LocalEmbedDepth = compositionLocalOf { 0 }

/**
 * `![[Another note]]`, drawn in place the way Obsidian draws it.
 *
 * It was offered to "another app" as an attachment, which had nothing to open.
 * The header is always there and opens the note, at the heading when the embed
 * names one; the body is the note itself, or the section the heading names,
 * rendered with the same blocks as everything else.
 */
@Composable
internal fun NoteEmbedView(
    block: MdBlock.NoteEmbed,
    actions: RenderActions,
    modifier: Modifier = Modifier,
) {
    val depth = LocalEmbedDepth.current
    val loader by rememberUpdatedState(actions.transclude?.takeIf { depth < MAX_EMBED_DEPTH })
    // Loading and "cannot be had" are different things to draw, so the state
    // says which rather than being a bare nullable.
    val embedded by produceState<EmbedState>(EmbedState.Loading, block.target, block.heading, actions.vaultId) {
        val load = loader
        value =
            if (load == null) {
                EmbedState.LinkOnly
            } else {
                load(block.target, block.heading)?.let { EmbedState.Loaded(it) } ?: EmbedState.Missing
            }
    }
    val accent = MaterialTheme.colorScheme.outlineVariant
    val reading = LocalReading.current

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, accent, RoundedCornerShape(8.dp)),
    ) {
        val loaded = (embedded as? EmbedState.Loaded)?.note
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(role = Role.Button) { actions.inline.onWikiLink(block.target, block.heading) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideGlyph(
                name = "file-text",
                size = EMBED_ICON * reading.textScale,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = loaded?.title?.takeIf { it.isNotBlank() } ?: block.label,
                    style = MaterialTheme.typography.labelLarge.inScript(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    when {
                        embedded == EmbedState.Missing -> stringResource(R.string.link_broken)
                        block.heading != null -> stringResource(R.string.link_at_heading, block.heading.orEmpty())
                        else -> null
                    }
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.inScript(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        loaded?.let { note ->
            val inner =
                actions.copy(
                    // A box ticked here would be ticked in the note on screen,
                    // at the embedded note's line number -- a different task.
                    onCompleteTask = null,
                    inline =
                        actions.inline.copy(
                            onWikiLink = { target, heading ->
                                val id = if (target.isBlank()) note.noteId else note.linkTargets[target]
                                if (id != null) {
                                    actions.inline.onNoteLink(id, heading)
                                } else {
                                    actions.inline.onBrokenLink(target)
                                }
                            },
                            // A note it links to is looked up in its own table;
                            // anything else -- a file -- goes on as it was.
                            onInternalLink = { destination ->
                                val link = MarkdownLinks.internal(destination)
                                val id = if (link.target.isEmpty()) note.noteId else note.linkTargets[link.target]
                                if (id != null) {
                                    actions.inline.onNoteLink(id, link.heading)
                                } else {
                                    actions.inline.onInternalLink(destination)
                                }
                            },
                        ),
                )
            CompositionLocalProvider(LocalEmbedDepth provides depth + 1) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    note.blocks.take(MAX_EMBED_BLOCKS).forEach { child ->
                        MdBlockView(child, inner, note.brokenLinks)
                    }
                    if (note.blocks.size > MAX_EMBED_BLOCKS) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyLarge.inScript(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface EmbedState {
    data object Loading : EmbedState

    /** Too deep, or nowhere to load from: the header alone, as a link. */
    data object LinkOnly : EmbedState

    /** The note, or the heading in it, is not there. */
    data object Missing : EmbedState

    class Loaded(
        val note: Transcluded,
    ) : EmbedState
}

private const val MAX_EMBED_DEPTH = 1

/**
 * An embed is one item of the note's lazy list, so everything in it is
 * composed at once. A whole long note embedded in another would stall the
 * frame; past this the header is the way in.
 */
private const val MAX_EMBED_BLOCKS = 80
private val EMBED_ICON = 18.dp
