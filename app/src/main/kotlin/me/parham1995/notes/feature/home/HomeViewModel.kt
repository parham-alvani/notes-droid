package me.parham1995.notes.feature.home

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.HomePictureStore
import me.parham1995.notes.data.ObsidianConfigStore
import me.parham1995.notes.data.VaultBookmarks
import me.parham1995.notes.data.VaultFileSource
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.obsidian.Beautitab
import me.parham1995.notes.obsidian.Bookmark
import javax.inject.Inject
import kotlin.random.Random

/** A bookmarked note the home page lists: one that is in this vault. */
data class HomeBookmark(
    val noteId: Long,
    val title: String,
    val heading: String?,
)

@Immutable
data class HomeUiState(
    val settings: Beautitab = Beautitab(),
    val recent: List<NoteEntity> = emptyList(),
    val bookmarks: List<HomeBookmark> = emptyList(),
    val quote: Beautitab.Quote? = null,
)

/**
 * The home page: what the vault's Beautitab settings ask it to show, and the
 * picture behind it.
 *
 * The picture is its own flow, apart from the rest, so a sync that changes
 * the recent notes does not ask for it again -- and when it is asked, the
 * store hands back the bitmap it already decoded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        repository: VaultRepository,
        configs: ObsidianConfigStore,
        private val pictures: HomePictureStore,
        private val files: VaultFileSource,
    ) : ViewModel() {
        /** Which quote, chosen once for as long as the page is: not a new one on each sync. */
        private val pick = Random.nextInt(Int.MAX_VALUE)

        private val settings = configs.beautitab(repository.activeVaultId)

        val state: StateFlow<HomeUiState> =
            combine(
                settings,
                repository.recentlyOpened(RECENT),
                configs.bookmarks(repository.activeVaultId),
            ) { tab, recent, bookmarks ->
                HomeUiState(
                    settings = tab,
                    recent = if (tab.showRecentFiles) recent else emptyList(),
                    bookmarks = if (tab.showBookmarks) notes(bookmarks).take(RECENT) else emptyList(),
                    quote = tab.quotes.takeIf { tab.showQuote && it.isNotEmpty() }?.let { it[pick % it.size] },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), HomeUiState())

        /**
         * The picture: the one chosen in Settings, else the Beautitab
         * plugin's own background when it is a picture in the vault, else none.
         */
        val picture: StateFlow<ImageBitmap?> =
            combine(pictures.file, settings, repository.activeVaultId) { chosen, tab, vaultId ->
                chosen ?: tab.vaultBackground?.let { files.localFile(vaultId, it) }
            }.distinctUntilChanged()
                .mapLatest { file -> file?.let { pictures.bitmap(it) }?.asImageBitmap() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

        /** Makes the photo at [uri] the home page's; [done] says whether it could be read. */
        fun choosePicture(
            uri: Uri,
            done: (Boolean) -> Unit,
        ) {
            viewModelScope.launch { done(pictures.choose(uri)) }
        }

        fun clearPicture() {
            viewModelScope.launch { pictures.clear() }
        }

        private fun notes(bookmarks: VaultBookmarks): List<HomeBookmark> {
            fun walk(items: List<Bookmark>): List<HomeBookmark> =
                items.flatMap { item ->
                    when (item) {
                        is Bookmark.File ->
                            listOfNotNull(
                                bookmarks.noteIds[item.path]?.let {
                                    HomeBookmark(it, item.label, item.heading)
                                },
                            )
                        is Bookmark.Group -> walk(item.items)
                        else -> emptyList()
                    }
                }
            return walk(bookmarks.items)
        }

        private companion object {
            /** How many of each list: enough for a page, not a second browser. */
            const val RECENT = 8
            const val STOP_MS = 5_000L
        }
    }
