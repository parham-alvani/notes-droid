package me.parham1995.notes.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import me.parham1995.notes.data.Destination
import me.parham1995.notes.data.Destinations
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.obsidian.ObsidianUri
import me.parham1995.notes.obsidian.Period
import java.time.LocalDate
import javax.inject.Inject

/**
 * Where the app is asked to go by something other than a tap on a row: the
 * "today" button and launcher shortcut, and `obsidian://` links from other
 * apps.
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
        private val repository: VaultRepository,
    ) : ViewModel() {
        /**
         * How long the vault being read's daily note covers, so "today" can
         * say "this week" when a note is a week.
         */
        val dailyPeriod: StateFlow<Period> =
            destinations
                .dailyPeriod()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), Period.DAY)

        /** Today's daily note in the vault being read, or where it would be. */
        suspend fun today(): Destination = destinations.dailyNote(LocalDate.now())

        /**
         * Where [uri] points, with the vault it names made the one being read
         * -- everything downstream reads the active vault, so a note opened
         * in any other would resolve its links against the wrong one. Null
         * when it is not a link this app follows.
         */
        suspend fun follow(uri: String): Destination? {
            val link = ObsidianUri.parse(uri) ?: return null
            val found = destinations.follow(link)
            found.vaultId?.let { id -> if (id != repository.activeVaultId.first()) repository.setActiveVault(id) }
            return found
        }

        private val Destination.vaultId: Long?
            get() =
                when (this) {
                    is Destination.Note -> vaultId
                    is Destination.NoDailyNote -> vaultId
                    is Destination.Search -> vaultId
                    is Destination.Vault -> vaultId
                    is Destination.UnknownNote -> vaultId
                    is Destination.UnknownVault -> null
                }

        private companion object {
            const val STOP_AFTER_MS = 5_000L
        }
    }
