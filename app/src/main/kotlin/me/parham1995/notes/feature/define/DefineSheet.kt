package me.parham1995.notes.feature.define

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.dictionary.Entry
import me.parham1995.notes.dictionary.PartOfSpeech
import me.parham1995.notes.dictionary.Sense
import me.parham1995.notes.ui.icon.LucideGlyph
import java.util.Locale

/**
 * The word, its senses, and a field to look up another. A synonym is a chip,
 * and tapping it looks that word up in its place.
 *
 * The entries are English whatever language the phone is in, so they are
 * laid out left to right even on a Persian phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefineSheet(
    viewModel: DefineViewModel,
    onDismiss: () -> Unit,
) {
    val definition by viewModel.definition.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(definition?.asked.orEmpty()) }
    // The field follows a lookup started elsewhere: a synonym, a new selection.
    LaunchedEffect(definition?.asked) { definition?.asked?.let { query = it } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val pronounce = rememberPronouncer()

    fun lookUp(text: String) {
        keyboard?.hide()
        viewModel.lookUp(text)
    }

    // Opened with nothing selected -- from the launcher -- the field is the point.
    LaunchedEffect(Unit) { if (definition == null) focus.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Light icons on the dark sheet, as in the reader. Left to follow the
        // system, a phone in light mode drew the navigation buttons dark on dark.
        properties =
            ModalBottomSheetProperties(
                isAppearanceLightStatusBars = false,
                isAppearanceLightNavigationBars = false,
            ),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.define_hint)) },
                leadingIcon = { LucideGlyph("search", size = 20.dp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { lookUp(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )
        }
        val shown = definition
        val entries = shown?.entries
        when {
            shown == null -> Unit
            entries == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
            entries.isEmpty() -> NotFound(shown)
            else ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                            item { EntryHeading(entry, onPronounce = { pronounce(entry.word) }) }
                            itemsIndexed(entry.senses) { number, sense ->
                                SenseView(number + 1, sense, onSynonym = ::lookUp)
                            }
                        }
                        item {
                            Text(
                                stringResource(R.string.define_source),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun NotFound(definition: Definition) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val message =
            when {
                definition.failed -> stringResource(R.string.define_failed)
                else -> stringResource(R.string.define_none, definition.asked)
            }
        Text(message, style = MaterialTheme.typography.bodyLarge)
        // A Persian word finds nothing because nothing Persian is in it, not
        // because of the spelling -- worth saying, so no one tries again.
        if (!definition.failed && definition.asked.none { it in 'a'..'z' || it in 'A'..'Z' }) {
            Text(
                stringResource(R.string.define_english_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryHeading(
    entry: Entry,
    onPronounce: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.word, style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(labelOf(entry.partOfSpeech)),
            style = MaterialTheme.typography.titleSmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        IconButton(onClick = onPronounce) {
            LucideGlyph("volume-2", size = 20.dp, contentDescription = stringResource(R.string.define_pronounce))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SenseView(
    number: Int,
    sense: Sense,
    onSynonym: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sense.definition, style = MaterialTheme.typography.bodyLarge)
            sense.examples.forEach { example ->
                Text(
                    "“$example”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sense.synonyms.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sense.synonyms.forEach { synonym ->
                        SuggestionChip(onClick = { onSynonym(synonym) }, label = { Text(synonym) })
                    }
                }
            }
        }
    }
}

private fun labelOf(partOfSpeech: PartOfSpeech): Int =
    when (partOfSpeech) {
        PartOfSpeech.NOUN -> R.string.define_noun
        PartOfSpeech.VERB -> R.string.define_verb
        PartOfSpeech.ADJECTIVE -> R.string.define_adjective
        PartOfSpeech.ADVERB -> R.string.define_adverb
    }

/**
 * Says a word aloud in English, through the phone's text-to-speech engine.
 * The engine starts asynchronously; a tap before it is ready says nothing,
 * which is better than holding the tap and speaking late.
 */
@Composable
private fun rememberPronouncer(): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    val pronouncer = remember { Pronouncer(context) }
    DisposableEffect(Unit) { onDispose { pronouncer.shutdown() } }
    return pronouncer::say
}

private class Pronouncer(
    context: Context,
) {
    @Volatile private var ready = false
    private val tts: TextToSpeech

    init {
        tts =
            TextToSpeech(context) { status ->
                ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
            }
    }

    fun say(word: String) {
        if (ready) tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, word)
    }

    fun shutdown() = tts.shutdown()
}
