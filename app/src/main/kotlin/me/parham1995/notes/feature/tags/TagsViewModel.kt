package me.parham1995.notes.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.markdown.TagTree
import me.parham1995.notes.markdown.TagTreeNode
import javax.inject.Inject

/** One tag's notes, once it has been chosen. */
data class TaggedNotes(
    val tag: String,
    val notes: List<NoteEntity>,
    val loading: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TagsViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
    ) : ViewModel() {
        /** Which vault's tags these are. Null until [start] has decided. */
        private val vault = MutableStateFlow<Long?>(null)

        /** The tree as rows with their depth, re-read whenever the index changes. */
        val rows: StateFlow<List<Pair<TagTreeNode, Int>>?> =
            vault
                .filterNotNull()
                .flatMapLatest { repository.tagTree(it) }
                .map { TagTree.flatten(it) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MILLIS), null)

        private val _selected = MutableStateFlow<TaggedNotes?>(null)
        val selected: StateFlow<TaggedNotes?> = _selected.asStateFlow()

        /**
         * [vaultId] is the vault of the note the tag was tapped in, or
         * [ACTIVE_VAULT] for the one being browsed. A tag belongs to a vault
         * like everything else, and the note on screen need not be in the one
         * the browser is showing.
         */
        fun start(
            vaultId: Long,
            tag: String,
        ) {
            viewModelScope.launch {
                vault.value = if (vaultId == ACTIVE_VAULT) repository.activeVaultId.first() else vaultId
                select(tag.takeIf { it.isNotBlank() })
            }
        }

        fun select(tag: String?) {
            if (tag == null) {
                _selected.value = null
                return
            }
            _selected.value = TaggedNotes(tag, emptyList(), loading = true)
            viewModelScope.launch {
                val id = vault.value ?: repository.activeVaultId.first()
                val found = repository.notesTagged(id, tag)
                if (_selected.value?.tag == tag) _selected.value = TaggedNotes(tag, found)
            }
        }

        companion object {
            /** In a route, "whichever vault is being read". Vault ids start at zero. */
            const val ACTIVE_VAULT = -1L
            private const val STOP_MILLIS = 5_000L
        }
    }
