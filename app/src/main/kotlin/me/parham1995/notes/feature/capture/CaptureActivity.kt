package me.parham1995.notes.feature.capture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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

    private val themes: Flow<ThemeChoice?> by lazy {
        settingsStore.settings.map<VaultSettings, ThemeChoice?> { it.reading.theme }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation keeps the field's own saved text; the intent it was
        // started with has already been put there.
        val shared = if (savedInstanceState == null) sharedText(intent) else ""

        setContent {
            // The person's theme, like the rest of the app. Nothing is drawn
            // until it is known, so a light phone does not flash the dark one.
            val theme by themes.collectAsStateWithLifecycle(initialValue = null)
            val chosen = theme ?: return@setContent
            NotesTheme(theme = chosen) {
                CaptureDialog(
                    initial = shared,
                    incoming = incoming,
                    onDone = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

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
        val body =
            when (intent.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                Intent.ACTION_PROCESS_TEXT ->
                    intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()

                else -> ""
            }
        // A shared page arrives as a title and a URL in two extras, and the
        // title on its own is the half that is no use later.
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        return listOf(subject, body).filter { it.isNotBlank() }.joinToString(" - ")
    }
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
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // By serial, so the same share is not merged twice. Not saved: the
    // shares live on the activity, and a recreated one starts with none.
    var merged by remember { mutableIntStateOf(0) }
    val arriving by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(arriving) {
        arriving?.takeIf { it.serial > merged }?.let {
            text = mergeShared(text, it.text)
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
                onClick = { viewModel.save(text) },
                enabled = state.ready && text.isNotBlank() && !state.saving,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val problem =
                    state.refused
                        ?: state.blocked?.let { stringResource(it, state.blockedVault) }
                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.capture_hint)) },
                    enabled = state.ready,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = FIELD_HEIGHT)
                            .focusRequester(focus),
                )
                if (state.destination.isNotBlank()) {
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
