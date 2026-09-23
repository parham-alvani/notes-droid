package me.parham1995.notes.feature.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * A query once typing has paused, paired with the vault it is to be asked of.
 *
 * The vault is an input, not something read on the side. A search is a
 * question about one vault, and a pipeline that re-ran only when the text
 * changed went on showing the last vault's answers after switching to another
 * -- notes that were not in the vault on screen, and nothing to say so.
 */
@OptIn(FlowPreview::class)
internal fun Flow<String>.settledIn(
    vault: Flow<Long>,
    debounceMs: Long,
): Flow<Pair<String, Long>> =
    combine(debounce(debounceMs), vault) { query, vaultId -> query to vaultId }
        .distinctUntilChanged()

/**
 * What a search has found so far. A null list is one that is not being
 * replaced by this step, so the rows on screen stay until their successors
 * arrive rather than blanking between keystrokes.
 */
internal data class SearchStep<Q, H>(
    val quick: List<Q>? = null,
    val hits: List<H>? = null,
    val searching: Boolean = false,
)

/**
 * Runs both searches for each settled query, in the order they are wanted:
 * the quick switcher first, because it answers most searches, and the
 * full-text pass under it.
 *
 * Latest-wins, so a search overtaken by another query or another vault is
 * abandoned rather than finishing and writing its now-wrong answer over the
 * right one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <Q, H> Flow<String>.searchSteps(
    vault: Flow<Long>,
    debounceMs: Long,
    quick: suspend (query: String, vaultId: Long) -> List<Q>,
    full: suspend (query: String, vaultId: Long) -> List<H>,
): Flow<SearchStep<Q, H>> =
    settledIn(vault, debounceMs).transformLatest { (query, vaultId) ->
        if (query.isBlank()) {
            emit(SearchStep(quick = emptyList(), hits = emptyList(), searching = false))
            return@transformLatest
        }
        emit(SearchStep(searching = true))
        emit(SearchStep(quick = quick(query, vaultId), searching = true))
        emit(SearchStep(hits = full(query, vaultId), searching = false))
    }
