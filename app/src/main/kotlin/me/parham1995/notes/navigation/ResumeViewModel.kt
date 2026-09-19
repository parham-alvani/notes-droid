package me.parham1995.notes.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import me.parham1995.notes.feature.note.NoteTabs
import javax.inject.Inject

/**
 * Just enough of the open tabs for the app to reopen where it was.
 *
 * The nav host is the only thing that can navigate at launch, and it is a
 * plain composable with nothing injected into it. This is the smallest hole to
 * pass the tabs through rather than reaching for an entry point.
 */
@HiltViewModel
class ResumeViewModel
    @Inject
    constructor(
        private val tabs: NoteTabs,
    ) : ViewModel() {
        /** True once what was written down has been read back. */
        val restored: StateFlow<Boolean> = tabs.restored

        /** The note that was being read, or null if none was. */
        fun activeNote(): Long? =
            tabs.state.value.current
                ?.noteId
    }
