package me.parham1995.notes.feature.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.BaseAnswer
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.render.BaseTables
import me.parham1995.notes.ui.render.MdBlockView
import me.parham1995.notes.ui.render.RenderActions
import me.parham1995.notes.ui.render.Transcluded
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

/**
 * What a held `[[link]]` points at, drawn: the note, or the section its
 * heading names, or the block its `^id` names, read in place without leaving
 * the page. The rendered blocks are inert -- a tap inside the preview opening
 * something else would be a second leap from a look.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkPreview(
    target: LinkTarget,
    content: Transcluded,
    vaultId: Long,
    onDismiss: () -> Unit,
    onOpenHere: () -> Unit,
    onOpenInNewTab: () -> Unit,
) {
    val actions = remember(vaultId) { RenderActions(vaultId = vaultId) }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("link-preview"),
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
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = PREVIEW_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content.blocks.take(PREVIEW_BLOCKS).forEach { block ->
                    MdBlockView(block, actions, content.brokenLinks)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenHere) { Text(stringResource(R.string.link_open_here)) }
                OutlinedButton(onClick = onOpenInNewTab) { Text(stringResource(R.string.link_open_new_tab)) }
            }
        }
    }
}

/** Enough of a long note to read the gist of; the button is there for the rest. */
private const val PREVIEW_BLOCKS = 60
private val PREVIEW_HEIGHT = 480.dp

/**
 * A `[[x.base]]` link, followed: the base's tables in a sheet, [view] alone
 * when the link named one. A name in a table opens its note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BaseSheet(
    title: String,
    view: String?,
    vaultId: Long,
    run: suspend () -> BaseAnswer?,
    onDismiss: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val answer by produceState<Result<BaseAnswer?>?>(null, title, view) { value = runCatching { run() } }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = sheetProperties()) {
        BaseTables(
            title = title,
            answer = answer,
            onlyView = view,
            vaultId = vaultId,
            onOpen = { path ->
                answer
                    ?.getOrNull()
                    ?.noteIds
                    ?.get(path)
                    ?.let(onOpen)
            },
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
        )
    }
}

/**
 * A link that resolves to nothing, said plainly rather than ignored, with a
 * button that makes the note it names -- as a click on one does on the Mac.
 * [onCreate] is null where the vault cannot be written, which hides it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrokenLinkPeek(
    target: String,
    onDismiss: () -> Unit,
    onCreate: (() -> Unit)? = null,
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
            onCreate?.let { create ->
                Button(onClick = create) { Text(stringResource(R.string.link_create_note)) }
            }
        }
    }
}
