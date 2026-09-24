package me.parham1995.notes.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import me.parham1995.notes.data.Destination
import me.parham1995.notes.data.Destinations
import java.time.LocalDate
import javax.inject.Inject

/**
 * Where the app is asked to go by something other than a tap on a row: the
 * "today" button and launcher shortcut.
 *
 * At the nav host because only it can navigate, and a plain composable has
 * nothing injected into it; the finding itself is [Destinations], in the data
 * layer where it is tested.
 */
@HiltViewModel
class JumpViewModel
    @Inject
    constructor(
        private val destinations: Destinations,
    ) : ViewModel() {
        /** Today's daily note in the vault being read, or where it would be. */
        suspend fun today(): Destination = destinations.dailyNote(LocalDate.now())
    }
