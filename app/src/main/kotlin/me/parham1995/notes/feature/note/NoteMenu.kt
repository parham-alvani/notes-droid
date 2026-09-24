package me.parham1995.notes.feature.note

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.PinStore
import me.parham1995.notes.ui.icon.LucideGlyph
import javax.inject.Inject

/**
 * The things done to a note rather than in it, behind one button.
 *
 * The top bar already carries find, connections and the outline, and a phone
 * gives the title whatever is left. Pinning is done once per note and sharing
 * rarely, so both live here instead of taking two more buttons' worth of the
 * title.
 */
@Composable
fun NoteMenu(
    enabled: Boolean,
    pinned: Boolean,
    onShare: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            LucideGlyph(
                "ellipsis-vertical",
                size = 20.dp,
                contentDescription = stringResource(R.string.note_more),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.note_unpin else R.string.note_pin)) },
                leadingIcon = { LucideGlyph(if (pinned) "pin-off" else "pin", size = 18.dp) },
                onClick = {
                    open = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { LucideGlyph("share-2", size = 18.dp) },
                onClick = {
                    open = false
                    onShare()
                },
            )
        }
    }
}

/**
 * Whether the note on screen is pinned to the home screen.
 *
 * Its own view model rather than more of [NoteViewModel]'s: the pin belongs to
 * the note's vault and path, which the note screen already has, and nothing
 * else about reading a note cares.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PinViewModel
    @Inject
    constructor(
        private val pins: PinStore,
    ) : ViewModel() {
        private val note = MutableStateFlow<Pin?>(null)

        val pinned: StateFlow<Boolean> =
            note
                .flatMapLatest { pin -> pin?.let { pins.isPinned(it.vaultId, it.path) } ?: flowOf(false) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        /** Follows the note now on screen. */
        fun track(
            vaultId: Long,
            path: String,
        ) {
            note.value = Pin(vaultId, path)
        }

        fun toggle() {
            val pin = note.value ?: return
            viewModelScope.launch { pins.toggle(pin.vaultId, pin.path) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
