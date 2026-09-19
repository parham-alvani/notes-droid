package me.parham1995.notes.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VaultIcon(spec = icon, default = defaultIcon, contentDescription = iconDescription)
        Column(Modifier.weight(1f)) {
            AutoDirection(title) {
                Text(
                    text = title,
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
