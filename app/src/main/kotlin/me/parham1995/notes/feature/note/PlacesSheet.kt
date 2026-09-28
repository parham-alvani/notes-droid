package me.parham1995.notes.feature.note

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.markdown.Contacts
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.SpokenText
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.render.ContactLinks

/**
 * The places a note mentions, gathered in one list, each a tap from the maps
 * app and a button from the clipboard.
 *
 * Found the way the page finds them -- the phone's text classifier, paragraph
 * by paragraph -- so this lists exactly what is underlined there, without
 * scrolling a long note to find the one with the flat's address in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesSheet(
    blocks: List<MdBlock>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Null while the classifier is still reading the note.
    val places by produceState<List<String>?>(null, blocks) {
        value =
            SpokenText
                .of(blocks)
                .flatMap { utterance ->
                    ContactLinks
                        .find(context, utterance.text)
                        .filter { it.kind == Contacts.Kind.ADDRESS }
                        .map { utterance.text.substring(it.start, it.end).trim() }
                }.distinct()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.places_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            val found = places
            when {
                found == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
                found.isEmpty() ->
                    Text(
                        stringResource(R.string.places_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                else ->
                    found.forEach { place ->
                        ListItem(
                            headlineContent = { Text(place) },
                            leadingContent = { LucideGlyph("map-pin", size = 20.dp) },
                            trailingContent = {
                                IconButton(onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipData.newPlainText(place, place).toClipEntry(),
                                        )
                                    }
                                }) {
                                    LucideGlyph(
                                        "copy",
                                        size = 18.dp,
                                        contentDescription = stringResource(R.string.places_copy),
                                    )
                                }
                            },
                            modifier =
                                Modifier.clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Contacts.geo(place).toUri()))
                                    }
                                },
                        )
                    }
            }
        }
    }
}
