package me.parham1995.notes.feature.define

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.runCatchingUnlessCancelled
import me.parham1995.notes.dictionary.Dictionary
import me.parham1995.notes.dictionary.Entry
import javax.inject.Inject

/** What was asked, and what the dictionary said -- null while it is still looking. */
data class Definition(
    val asked: String,
    val entries: List<Entry>?,
    val failed: Boolean = false,
)

@HiltViewModel
class DefineViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _definition = MutableStateFlow<Definition?>(null)
        val definition: StateFlow<Definition?> = _definition.asStateFlow()

        private val opening = Mutex()
        private var opened: Dictionary? = null
        private var lookup: Job? = null

        fun lookUp(text: String) {
            val asked = text.trim()
            lookup?.cancel()
            if (asked.isEmpty()) {
                _definition.value = null
                return
            }
            _definition.value = Definition(asked, entries = null)
            lookup =
                viewModelScope.launch {
                    val found =
                        runCatchingUnlessCancelled { withContext(Dispatchers.IO) { dictionary().define(asked) } }
                    _definition.value = Definition(asked, found.getOrNull() ?: emptyList(), failed = found.isFailure)
                }
        }

        /** Opened on the first lookup, and kept for the ones that follow a synonym. */
        private suspend fun dictionary(): Dictionary =
            opening.withLock { opened ?: BundledDictionary.open(context).also { opened = it } }

        override fun onCleared() {
            opened?.close()
        }
    }
