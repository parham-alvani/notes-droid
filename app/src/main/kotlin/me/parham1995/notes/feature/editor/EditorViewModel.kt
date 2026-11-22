package me.parham1995.notes.feature.editor

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
 */
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        private val files: VaultFileStore,
        private val writes: VaultWriteRepository,
    ) : ViewModel() {
        internal var session: EditorSession? = null
            private set

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
                viewModelScope.launch { it.open(heading) }
            }

        /** Saves, then [onDone] with whether anything was written, when the editor can close. */
        fun close(onDone: (saved: Boolean) -> Unit) {
            val open = session ?: return onDone(false)
            val dirty = open.dirty
            viewModelScope.launch {
                if (open.save()) {
                    session = null
                    onDone(dirty)
                }
            }
        }

        fun reload() {
            viewModelScope.launch { session?.reload() }
        }
    }
