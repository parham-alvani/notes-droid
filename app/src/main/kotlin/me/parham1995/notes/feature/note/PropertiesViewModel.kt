package me.parham1995.notes.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.parham1995.notes.data.BaseRepository
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.feature.tasks.describe
import me.parham1995.notes.markdown.FrontMatterForm
import me.parham1995.notes.markdown.PropertyEdit
import me.parham1995.notes.markdown.PropertySheet
import me.parham1995.notes.markdown.PropertyType
import me.parham1995.notes.markdown.PropertyValue
import me.parham1995.notes.ui.UiText
import javax.inject.Inject

/** The Properties form of the note on screen, as [PropertiesViewModel] read it. */
data class PropertiesState(
    val loading: Boolean = true,
    /** Null while loading, or when the note cannot be read. */
    val form: FrontMatterForm? = null,
    /** The front matter as written, shown when the form cannot show it. */
    val source: String = "",
    /** `.obsidian/types.json`. */
    val types: Map<String, String> = emptyMap(),
    /** The type picked for a property added here, kept while it is empty. */
    val chosen: Map<String, PropertyType> = emptyMap(),
    /** The vault's property names, the most used first, once asked for. */
    val names: List<String> = emptyList(),
    /** A write is on its way. */
    val writing: Boolean = false,
    /**
     * An edit waits behind a refresh and is not in the file yet, so the form
     * shows what was there before it. Another edit would be anchored on that
     * and refused when the queue runs, so the form takes no more.
     */
    val waiting: Boolean = false,
    val message: UiText? = null,
) {
    /** The type [key] is shown as: the one picked for it, else Obsidian's. */
    fun typeOf(
        key: String,
        value: PropertyValue,
    ): PropertyType =
        chosen[key]?.takeIf { value == PropertyValue.Empty || it.isList == (value is PropertyValue.Items) }
            ?: PropertyType.infer(key, value, types)
}

/**
 * The front matter of a note as a form: a row for each property, changed one
 * edit at a time -- `properties.md` in notes-linux's `docs/specs/`.
 *
 * Each edit is queued with the front matter it was read from, and is refused
 * when the note's front matter has changed since; then, as after every
 * edit, the form is read again from the note as it is now.
 */
@HiltViewModel
class PropertiesViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val bases: BaseRepository,
        private val writes: VaultWriteRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(PropertiesState())
        val state: StateFlow<PropertiesState> = _state.asStateFlow()

        private var noteId = 0L
        private var vaultId = 0L
        private var path = ""

        /** Reads the form of note [id], at [notePath] in [vault]. */
        fun open(
            id: Long,
            vault: Long,
            notePath: String,
        ) {
            noteId = id
            vaultId = vault
            path = notePath
            _state.value = PropertiesState()
            viewModelScope.launch {
                val types = bases.propertyTypes(vault)
                _state.update { it.copy(types = types) }
                read()
            }
        }

        private suspend fun read() {
            val text = repository.markdown(noteId)
            _state.update {
                it.copy(
                    loading = false,
                    form = text?.let(PropertySheet::read),
                    source = text?.take(PropertySheet.length(text)).orEmpty(),
                )
            }
        }

        /** The vault's property names, for completing a new one; read once. */
        fun loadNames() {
            if (_state.value.names.isNotEmpty()) return
            viewModelScope.launch {
                val names = bases.propertyNames(vaultId)
                _state.update { it.copy(names = names) }
            }
        }

        /**
         * Makes [edit], and reads the form again from what the note says
         * after it. [type] is the type picked for a property being added.
         * [onWritten] runs when the note changed, for the page to redraw it.
         */
        fun edit(
            edit: PropertyEdit,
            type: PropertyType? = null,
            onWritten: () -> Unit = {},
        ) {
            val sheet = _state.value.form?.sheet ?: return
            if (_state.value.writing || _state.value.waiting) return
            _state.update { state ->
                state.copy(
                    writing = true,
                    chosen =
                        when (edit) {
                            is PropertyEdit.Add -> if (type != null) state.chosen + (edit.key to type) else state.chosen
                            is PropertyEdit.Remove -> state.chosen - edit.key
                            is PropertyEdit.Rename ->
                                state.chosen[edit.key]?.let { state.chosen - edit.key + (edit.to to it) }
                                    ?: state.chosen
                            is PropertyEdit.Set -> state.chosen
                        },
                )
            }
            viewModelScope.launch {
                val result = writes.setProperty(vaultId, path, sheet.original, edit)
                val written = result == WriteResult.Pushed || result is WriteResult.Queued
                read()
                _state.update {
                    it.copy(
                        writing = false,
                        // Queued offline, the edit is in the file already and the form shows it;
                        // queued behind a refresh, the front matter read again is the old one.
                        waiting =
                            it.waiting || (result is WriteResult.Queued && it.form?.sheet?.original == sheet.original),
                        message =
                            when (result) {
                                WriteResult.Pushed -> null
                                WriteResult.Unchanged -> null
                                else -> result.describe()
                            },
                    )
                }
                if (written) onWritten()
            }
        }

        fun dismissMessage() = _state.update { it.copy(message = null) }
    }
