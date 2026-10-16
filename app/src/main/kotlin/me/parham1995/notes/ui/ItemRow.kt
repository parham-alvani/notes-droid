package me.parham1995.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.data.VaultItem
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.ui.icon.VaultIcon
import me.parham1995.notes.ui.theme.resolve

/** A vault item with its Iconic assignment already resolved. */
data class VaultRowItem(
    val item: VaultItem,
    val icon: IconSpec? = null,
)

/**
 * One line of a vault listing: icon, label, and an optional action on the end.
 *
 * Shared because the browser and a folder note's contents are the same list of
 * the same things, differing only in what tapping a folder should do. Keeping
 * one row means the icon column, the folder-note underline and the spacing
 * cannot drift apart between the two places they appear.
 */
@Composable
fun ItemRow(
    title: String,
    icon: IconSpec?,
    defaultIcon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Held rather than tapped. Null leaves the gesture alone. */
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null,
    iconDescription: String? = null,
    /** Obsidian's own mark for a folder that has a note of its own. */
    underline: Boolean = false,
    /** On the title alone: how a row shares it with the screen it opens. */
    titleModifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, defaultIcon, iconDescription)
        Column(Modifier.weight(1f)) {
            AutoDirection(title) {
                Text(
                    text = title,
                    modifier = titleModifier,
                    style = MaterialTheme.typography.bodyLarge.inScript(),
                    textDecoration = if (underline) TextDecoration.Underline else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * The row's icon on a tile washed in its colour.
 *
 * The colour says what kind of thing the row is before the title does, in the
 * roles the theme already gives them: a folder is orange like everything
 * structural, a note blue, an attachment yellow. An Iconic colour wins, because
 * the person chose it; navigation glyphs -- the way up, a group's chevron --
 * stay grey and untiled, since they are not things in the vault.
 */
@Composable
private fun IconTile(
    icon: IconSpec?,
    defaultIcon: String,
    description: String?,
) {
    val colors = MaterialTheme.colorScheme
    val role =
        when (defaultIcon) {
            "folder" -> colors.primary
            "file-text", "heading" -> colors.secondary
            "corner-left-up", "chevron-down", "chevron-right" -> null
            else -> colors.tertiary
        }
    val accent =
        when (icon) {
            is IconSpec.Glyph -> icon.color.resolve(role ?: colors.onSurfaceVariant)
            is IconSpec.Emoji -> null
            null -> role
        }
    if (role == null && icon !is IconSpec.Emoji) {
        VaultIcon(spec = icon, default = defaultIcon, contentDescription = description)
        return
    }
    Box(
        Modifier
            .size(TILE)
            .background(accent?.copy(alpha = TILE_WASH) ?: colors.surfaceContainerHigh, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        VaultIcon(
            spec = icon,
            default = defaultIcon,
            size = 18.dp,
            tint = accent ?: colors.onSurfaceVariant,
            contentDescription = description,
        )
    }
}

private val TILE = 34.dp
private const val TILE_WASH = 0.16f
