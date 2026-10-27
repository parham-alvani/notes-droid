package me.parham1995.notes.feature.capture

import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import me.parham1995.notes.R
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.markdown.HtmlMarkdown
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.theme.NotesTheme
import javax.inject.Inject

/**
 * Write one thing down, from anywhere.
 *
 * Its own activity rather than a screen in the app, because the two ways this
 * is actually reached -- sharing text into it and a button on the home screen
 * -- both mean "I have a thought, take it". Routing those through the reader
 * would put a vault browser between the thought and the field it goes in.
 *
 * It is a dialog over whatever was already on screen, and closing it is the
 * whole interaction.
 *
 * Opened on a locked phone -- from the Quick Settings tile -- it is drawn over
 * the lock screen rather than asking for the unlock first. That is safe
 * because a capture only ever adds: it queues an edit to the scratchpad and
 * reads nothing back. While the phone is locked the dialog shows nothing of the
 * vault or the phone either -- not where the line will land, and not the
 * clipboard paste, which would hand whoever picked the phone up whatever was
 * last copied.
 */
@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {
    @Inject
    lateinit var settingsStore: SettingsStore

    /**
     * Text handed over after the dialog was already open.
     *
     * The activity is singleTask, so a second share while it is showing arrives
     * in onNewIntent rather than starting another -- and before this was read,
     * the second share simply vanished.
     */
    private val incoming = MutableStateFlow<Shared?>(null)

    /** Whether the phone is locked behind this dialog; read again on every resume. */
    private val locked = MutableStateFlow(false)

    private val themes: Flow<ThemeChoice?> by lazy {
        settingsStore.settings.map<VaultSettings, ThemeChoice?> { it.reading.theme }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the lock screen only when it was opened over it. One opened on
        // an unlocked phone stays behind the lock when the screen goes off, so
        // a share typed into it is not shown to whoever wakes the phone.
        locked.value = deviceLocked()
        if (locked.value) setShowWhenLocked(true)
        // A recreation keeps the field's own saved text; the intent it was
        // started with has already been put there.
        val shared = if (savedInstanceState == null) sharedText(intent) else ""

        setContent {
            // The person's theme, like the rest of the app. Nothing is drawn
            // until it is known, so a light phone does not flash the dark one.
            val theme by themes.collectAsStateWithLifecycle(initialValue = null)
            val chosen = theme ?: return@setContent
            val onLockScreen by locked.collectAsStateWithLifecycle()
            NotesTheme(theme = chosen) {
                CaptureDialog(
                    initial = shared,
                    incoming = incoming,
                    locked = onLockScreen,
                    onDone = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        locked.value = deviceLocked()
    }

    /** A secure lock only: a swipe to unlock keeps nothing from anyone. */
    private fun deviceLocked(): Boolean = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText(intent).takeIf { it.isNotBlank() }?.let { text ->
            incoming.value = Shared(text, (incoming.value?.serial ?: 0) + 1)
        }
    }

    /**
     * Whatever another app handed over: shared text, a shared link, or a
     * selection sent through the text-processing menu.
     */
    private fun sharedText(intent: Intent?): String {
        if (intent == null) return ""
        // As CharSequence, not String: an app that shares styled text puts a
        // Spanned in the extra, and getStringExtra answers that with null.
        val body =
            when (intent.action) {
                Intent.ACTION_SEND ->
                    sharedBody(
                        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                        intent.getStringExtra(Intent.EXTRA_HTML_TEXT),
                    )
                Intent.ACTION_PROCESS_TEXT ->
                    intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()

                else -> ""
            }
        return sharedCapture(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(), body)
    }
}

/**
 * What a share says, as Markdown where it came as rich text: a page or a
 * document shared with its formatting keeps its headings, links and lists
 * rather than arriving flattened. HTML that holds nothing a plain copy would
 * lose -- a code editor's coloured lines -- is taken as the plain text.
 */
internal fun sharedBody(
    text: String?,
    html: String?,
): String = html?.let(HtmlMarkdown::convert) ?: text.orEmpty()

/**
 * A share as the line it should be in the vault.
 *
 * A shared page arrives as a title in the subject and a URL in the text, and
 * the two belong together as a link: `[Title](url)` reads as the title and
 * still goes to the page. A URL shared on its own becomes an autolink, `<url>`,
 * because a bare one fails the markdownlint the vault runs on every commit.
 * Apps that also put the title in the text -- "Title url", or the title and
 * the URL on two lines -- make the same link rather than the title twice.
 *
 * Anything else keeps the subject in front of it: on the same line for one
 * line of text, on a line of its own above several, so a multi-line share
 * stays the paragraphs it was. A subject the text already starts with is not
 * said again.
 */
internal fun sharedCapture(
    subject: String?,
    body: String,
): String {
    val title = subject?.trim().orEmpty()
    val text = body.trim()
    if (text.isEmpty()) return title
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val url = lines.last().substringAfterLast(' ').takeIf(WEB_URL::matches)
    if (url != null) {
        // What the text says before the URL: nothing, or the title again.
        val before = (lines.dropLast(1) + lines.last().removeSuffix(url).trim()).filter { it.isNotEmpty() }
        val named = before.joinToString(" ")
        // A line above the URL with no subject is the title too; a sentence
        // with a URL at its end ("look at this https://...") is not, and stays
        // the sentence it was.
        val titled = before.size == 1 && (title == named || (title.isEmpty() && lines.size == 2 && lines.last() == url))
        when {
            before.isEmpty() && title.isNotEmpty() && title != url -> return markdownLink(title, url)
            before.isEmpty() -> return "<$url>"
            titled -> return markdownLink(named, url)
        }
    }
    // The URL at the end, if there is one, as an autolink like the one above.
    val linked = if (url == null) text else text.removeSuffix(url) + "<$url>"
    return when {
        title.isEmpty() || text.startsWith(title) -> linked
        lines.size > 1 -> "$title\n$linked"
        else -> "$title - $linked"
    }
}

/**
 * `[text](url)`, with the brackets of [text] escaped so a title that has some
 * still ends where it should, and [url] in angle brackets when it holds a
 * parenthesis -- a Wikipedia page's would otherwise close the link early.
 */
private fun markdownLink(
    text: String,
    url: String,
): String {
    val label = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
    val target = if (url.any { it == '(' || it == ')' }) "<$url>" else url
    return "[$label]($target)"
}

/** One http(s) URL and nothing else: no space, no angle bracket. */
private val WEB_URL = Regex("""https?://[^\s<>]+""")

/**
 * [value] with [pasted] in place of its selection, and the caret after it,
 * as a paste from the keyboard would leave it.
 */
internal fun pasteInto(
    value: TextFieldValue,
    pasted: String,
): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val text = value.text.replaceRange(start, end, pasted)
    return TextFieldValue(text, TextRange(start + pasted.length))
}

/**
 * The clipboard as Markdown: its HTML converted where there is any worth
 * converting, its plain text otherwise, null when it holds no text at all.
 * Read only when asked -- Android tells the person every time an app reads it.
 */
private fun clipboardMarkdown(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return sharedBody(item.coerceToText(context)?.toString(), item.htmlText).takeIf { it.isNotEmpty() }
}

/** A share that arrived while the dialog was open, numbered so two alike are two. */
private data class Shared(
    val text: String,
    val serial: Int,
)

/**
 * Adds a second share to what is already in the field rather than replacing it:
 * whatever was there -- typed, or shared a moment ago -- is still wanted.
 */
internal fun mergeShared(
    current: String,
    arriving: String,
): String = if (current.isBlank()) arriving else current.trimEnd() + "\n" + arriving

@Composable
private fun CaptureDialog(
    initial: String,
    incoming: StateFlow<Shared?>,
    locked: Boolean,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // With its caret, so text that arrives -- a share merged in, or the one
    // the screen opened with -- leaves the caret after it, ready to go on.
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // By serial, so the same share is not merged twice. Not saved: the
    // shares live on the activity, and a recreated one starts with none.
    var merged by remember { mutableIntStateOf(0) }
    val arriving by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(arriving) {
        arriving?.takeIf { it.serial > merged }?.let {
            val joined = mergeShared(text.text, it.text)
            text = TextFieldValue(joined, TextRange(joined.length))
            merged = it.serial
        }
    }

    val finished = state.finished?.let { stringResource(it) }
    LaunchedEffect(finished) { finished?.let(onDone) }
    LaunchedEffect(state.ready) {
        // Straight into the field: this screen exists to be typed in, and a
        // capture that needs a tap to start is a capture that loses the thought.
        if (state.ready && initial.isBlank()) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_title)) },
        confirmButton = {
            TextButton(
                onClick = { viewModel.save(text.text) },
                enabled = state.ready && text.text.isNotBlank() && !state.saving,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Both name the vault, so the lock screen gets the fact and
                // not the reason.
                val problem =
                    (
                        state.refused
                            ?: state.blocked?.let { stringResource(it, state.blockedVault) }
                    )?.let { if (locked) stringResource(R.string.capture_locked_problem) else it }
                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.capture_hint)) },
                    enabled = state.ready,
                    // A thought is a sentence: it starts with a capital.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = FIELD_HEIGHT)
                            .focusRequester(focus),
                )
                // A paste from the keyboard is plain text; this one keeps a web
                // page's or a document's headings, links and lists, as the
                // Mac's paste does. Not on the lock screen: see the activity.
                val context = LocalContext.current
                if (!locked) {
                    TextButton(
                        onClick = { clipboardMarkdown(context)?.let { text = pasteInto(text, it) } },
                        enabled = state.ready,
                    ) {
                        LucideGlyph("clipboard-paste", size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.capture_paste_markdown))
                    }
                }
                if (state.destination.isNotBlank() && !locked) {
                    Text(
                        text = state.destination,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

private val FIELD_HEIGHT = 96.dp
