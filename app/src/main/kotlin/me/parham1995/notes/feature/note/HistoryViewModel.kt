package me.parham1995.notes.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.NoteHistoryRepository
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.data.describeChain
import me.parham1995.notes.data.runCatchingUnlessCancelled
import me.parham1995.notes.feature.tasks.describe
import me.parham1995.notes.sync.NoteVersion
import me.parham1995.notes.ui.UiText
import javax.inject.Inject

/** A note's history as the sheet shows it. */
data class HistoryState(
    val loading: Boolean = true,
    val versions: List<NoteVersion> = emptyList(),
    /** Why the history could not be read: no network, no token. */
    val failure: String? = null,
    /** The version being looked at, and its text once read. */
    val shown: NoteVersion? = null,
    val text: String? = null,
    val reading: Boolean = false,
    val writing: Boolean = false,
    val message: UiText? = null,
)

/**
 * A note's history, as Daftar for Linux and the Mac keep it: the commits that
 * changed it across renames (`git log --follow`), the note as it was at one,
 * and putting it back. A restore is an ordinary write -- the next commit --
 * so nothing in the history is lost by it.
 */
@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val history: NoteHistoryRepository,
        private val writes: VaultWriteRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(HistoryState())
        val state: StateFlow<HistoryState> = _state.asStateFlow()

        private var vaultId = 0L
        private var path = ""

        fun open(
            vault: Long,
            notePath: String,
        ) {
            vaultId = vault
            path = notePath
            _state.value = HistoryState()
            viewModelScope.launch {
                runCatchingUnlessCancelled { history.history(vault, notePath) }
                    .onSuccess { versions -> _state.update { it.copy(loading = false, versions = versions) } }
                    .onFailure { failure ->
                        _state.update { it.copy(loading = false, failure = failure.describeChain(frames = 0)) }
                    }
            }
        }

        /** Reads the note as it was at [version]. */
        fun show(version: NoteVersion) {
            _state.update { it.copy(shown = version, text = null, reading = true, message = null) }
            viewModelScope.launch {
                val text = runCatchingUnlessCancelled { history.content(vaultId, version) }
                _state.update {
                    if (it.shown != version) {
                        it
                    } else {
                        it.copy(
                            reading = false,
                            text = text.getOrNull(),
                            message =
                                text.exceptionOrNull()?.let { failure -> UiText.Raw(failure.describeChain(frames = 0)) }
                                    ?: UiText.Resource(R.string.history_not_there).takeIf { text.getOrNull() == null },
                        )
                    }
                }
            }
        }

        /** Back from a version to the list. */
        fun back() = _state.update { it.copy(shown = null, text = null, message = null) }

        /** Writes the version on screen back as the note; [onWritten] when it landed or is queued. */
        fun restore(onWritten: () -> Unit) {
            val state = _state.value
            val version = state.shown ?: return
            val text = state.text ?: return
            if (state.writing) return
            _state.update { it.copy(writing = true) }
            viewModelScope.launch {
                val result = writes.restore(vaultId, path, version.commit, text)
                _state.update {
                    it.copy(
                        writing = false,
                        message =
                            when (result) {
                                WriteResult.Pushed ->
                                    UiText.Resource(
                                        R.string.history_restored,
                                        listOf(version.commit.take(SHORT)),
                                    )
                                else -> result.describe()
                            },
                    )
                }
                if (result == WriteResult.Pushed || result is WriteResult.Queued) onWritten()
            }
        }

        private companion object {
            const val SHORT = 7
        }
    }
