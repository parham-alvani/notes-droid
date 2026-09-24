package me.parham1995.notes.ui.render

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.markdown.FrontMatterProperty
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.Tags
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript

/**
 * A note's front matter, as Obsidian's Properties view shows it: one row per
 * key, a list as chips, tags as tags that open their list.
 *
 * It used to be dropped entirely, which hid the dates, sources and status a
 * note carries at the top -- things written there precisely so they would be
 * seen first. Folded shut by a tap on its header, because a long block of
 * metadata above every note is also a thing to scroll past.
 */
@Composable
internal fun PropertiesView(
    block: MdBlock.FrontMatter,
    actions: RenderActions,
    modifier: Modifier = Modifier,
) {
    val shown = block.properties.filter { it.values.isNotEmpty() }
    if (shown.isEmpty()) return
    var expanded by remember(block.id) { mutableStateOf(true) }
    val expandedLabel = stringResource(R.string.state_expanded)
    val collapsedLabel = stringResource(R.string.state_collapsed)
    val scale = LocalReading.current.textScale
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, outline, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) expandedLabel else collapsedLabel }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideGlyph("sliders-horizontal", size = PROPERTY_ICON * scale)
            Text(
                text = stringResource(R.string.note_properties),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LucideGlyph(if (expanded) "chevron-down" else "chevron-right", size = PROPERTY_ICON * scale)
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEach { PropertyRow(it, actions) }
            }
        }
    }
}

@Composable
private fun PropertyRow(
    property: FrontMatterProperty,
    actions: RenderActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = property.key,
            modifier = Modifier.widthIn(min = KEY_WIDTH, max = KEY_WIDTH),
            style = MaterialTheme.typography.bodyMedium.inScript(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val isTags = property.key.lowercase() in TAG_KEYS
        when {
            isTags -> {
                // The same reading of the value the index makes, so a chip
                // opens exactly the tag the note is filed under.
                val tags = Tags.fromFrontMatter(listOf(property))
                Chips(tags.map { "#$it" to { actions.inline.onTag(it) } })
            }
            property.isList -> Chips(property.values.map { it to null })
            else ->
                Text(
                    text = property.values.joinToString(", "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.inScript(),
                )
        }
    }
}

@Composable
private fun Chips(items: List<Pair<String, (() -> Unit)?>>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (label, onClick) ->
            val tappable = onClick != null
            Surface(
                shape = RoundedCornerShape(12.dp),
                color =
                    if (tappable) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall.inScript(),
                    color =
                        if (tappable) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

private val TAG_KEYS = setOf("tags", "tag")
private val KEY_WIDTH = 96.dp
private val PROPERTY_ICON = 16.dp
