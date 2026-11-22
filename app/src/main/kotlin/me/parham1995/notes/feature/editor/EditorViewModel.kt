package me.parham1995.notes.feature.editor

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import me.parham1995.notes.data.VaultFileStore
import me.parham1995.notes.data.VaultWriteRepository
import javax.inject.Inject

/**
 * The editor's note: read from the vault's files, and saved through the one
 * place the app writes, so a save is one commit, as a tick is.
 *
 * What was typed is kept in the saved state while the app is out of sight
 * ([EditorSession.draft]), so the system ending the app there loses nothing:
 * the editor opens again with it.
 */
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        private val saved: SavedStateHandle,
        private val files: VaultFileStore,
        private val writes: VaultWriteRepository,
    ) : ViewModel() {
        internal var session: EditorSession? = null
            private set

        init {
            // Asked when the app leaves the screen, never on a key press.
            saved.setSavedStateProvider(DRAFT) { session?.draft()?.toBundle() ?: Bundle.EMPTY }
        }

        /** Opens [path] of [vaultId], once; [heading] is the number of the heading being read under. */
        internal fun open(
            vaultId: Long,
            path: String,
            heading: Int?,
        ): EditorSession =
            session ?: EditorSession(
                read = { files.readText(vaultId, path) },
                write = { opened, text -> writes.edit(vaultId, path, opened, text) },
            ).also {
                session = it
                val draft = saved.get<Bundle>(DRAFT)?.toDraft()
                if (draft != null) it.restore(draft) else viewModelScope.launch { it.open(heading) }
            }

        /**
         * Saves, then [onDone] when the editor can close: whether anything
         * was written, and the number of the heading the caret was under.
         */
        fun close(onDone: (saved: Boolean, heading: Int?) -> Unit) {
            val open = session ?: return onDone(false, null)
            val dirty = open.dirty
            viewModelScope.launch {
                if (open.save()) {
                    val heading = open.heading()
                    leave()
                    onDone(dirty, heading)
                }
            }
        }

        /** Closes with nothing written: what was typed is dropped. Ask first. */
        fun discard(onDone: (saved: Boolean, heading: Int?) -> Unit) {
            leave()
            onDone(false, null)
        }

        private fun leave() {
            session = null
            saved.remove<Bundle>(DRAFT)
        }

        fun reload() {
            viewModelScope.launch { session?.reload() }
        }

        private companion object {
            const val DRAFT = "editor-draft"
        }
    }

private const val OPENED = "opened"
private const val TEXT = "text"
private const val START = "start"
private const val END = "end"

internal fun EditorDraft.toBundle(): Bundle =
    Bundle().apply {
        putString(OPENED, opened)
        putString(TEXT, text)
        putInt(START, start)
        putInt(END, end)
    }

/** The draft a bundle holds, or null for one that holds none. */
internal fun Bundle.toDraft(): EditorDraft? {
    val opened = getString(OPENED) ?: return null
    val text = getString(TEXT) ?: return null
    return EditorDraft(opened, text, getInt(START), getInt(END))
}
