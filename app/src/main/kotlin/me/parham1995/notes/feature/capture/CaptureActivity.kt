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
import me.parham1995.notes.R
import me.parham1995.notes.ui.theme.NotesTheme

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = sharedText(intent)

        setContent {
            NotesTheme {
                CaptureDialog(
                    initial = shared,
                    onDone = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
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

@Composable
private fun CaptureDialog(
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.finished) { state.finished?.let(onDone) }
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
                state.blocked?.let {
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
