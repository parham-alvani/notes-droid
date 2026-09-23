package me.parham1995.notes.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import me.parham1995.notes.data.VaultIcons
import me.parham1995.notes.data.VaultItem
import me.parham1995.notes.data.VaultRepository

/** One folder's contents, each row with its icon, and which folder it is. */
data class FolderListing(
    val folder: String,
    val rows: List<VaultRowItem>,
)

/**
 * The contents of whichever folder [folder] names, in the vault being read.
 *
 * Follows the folder, the tree, the icon assignments and the vault, so a sync
 * that adds a note or a switch to another vault redraws the listing rather
 * than leaving a snapshot on screen. Icons are resolved here and not in the
 * row composable, which keeps the rule regexes off the composition: a rule is
 * tested against every visible row, and a list recomposes on every scroll.
 *
 * The browser and the drawer both walk folders this way; [order] is the one
 * thing they differ on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun VaultRepository.folderListing(
    folder: Flow<String>,
    icons: Flow<VaultIcons>,
    order: Flow<Comparator<VaultItem>?> = flowOf(null),
): Flow<FolderListing> =
    folder.flatMapLatest { at ->
        combine(childrenFlow(at), icons, activeVaultId, order) { children, config, vaultId, comparator ->
            val sorted = comparator?.let(children::sortedWith) ?: children
            FolderListing(at, sorted.map { VaultRowItem(it, config.forPath(vaultId, it.path, it.isFolder)) })
        }
    }
