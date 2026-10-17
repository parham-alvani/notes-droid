package me.parham1995.notes.feature.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.sheetProperties

/**
 * What a link points at, and what to do with it.
 *
 * The title and where it lives answer "is this the one I meant"; the opening
 * words answer it when two notes share a name, which in this vault is 110
 * times over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkPeek(
    target: LinkTarget,
    onDismiss: () -> Unit,
    onOpenHere: () -> Unit,
    onOpenInNewTab: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutoDirection(target.title) {
                Text(target.title, style = MaterialTheme.typography.titleMedium.inScript())
            }
            target.heading?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.link_at_heading, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                target.path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (target.excerpt.isNotBlank()) {
                AutoDirection(target.excerpt) {
                    Text(
                        target.excerpt,
                        style = MaterialTheme.typography.bodySmall.inScript(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenHere) { Text(stringResource(R.string.link_open_here)) }
                OutlinedButton(onClick = onOpenInNewTab) { Text(stringResource(R.string.link_open_new_tab)) }
            }
        }
    }
}

/** A link that resolves to nothing, said plainly rather than ignored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrokenLinkPeek(
    target: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.link_broken), style = MaterialTheme.typography.titleMedium)
            Text(
                target,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.link_broken_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
