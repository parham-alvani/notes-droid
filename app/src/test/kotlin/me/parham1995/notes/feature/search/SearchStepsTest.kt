package me.parham1995.notes.feature.search

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * That a search answers for the vault on screen.
 *
 * It re-ran only when the text changed, so switching vault with a query typed
 * kept showing the previous vault's notes -- results from somewhere else, with
 * nothing on screen to say so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchStepsTest {
    /** Rows are "vault:query", so a result says which vault answered it. */
    private fun answer(
        query: String,
        vaultId: Long,
    ) = listOf("$vaultId:$query")

    private data class Shown(
        val quick: List<String> = emptyList(),
        val hits: List<String> = emptyList(),
    )

    @Test
    fun `switching vault asks the new vault the same question`() =
        runTest {
            val queries = MutableStateFlow("")
            val vault = MutableStateFlow(1L)
            var shown = Shown()
            val job =
                launch {
                    queries
                        .searchSteps(vault, DEBOUNCE, ::answer, ::answer)
                        .collect { step ->
                            shown = Shown(step.quick ?: shown.quick, step.hits ?: shown.hits)
                        }
                }

            queries.value = "plan"
            advanceUntilIdle()
            assertThat(shown).isEqualTo(Shown(listOf("1:plan"), listOf("1:plan")))

            vault.value = 2L
            advanceUntilIdle()
            assertThat(shown).isEqualTo(Shown(listOf("2:plan"), listOf("2:plan")))
            job.cancel()
        }

    @Test
    fun `a search overtaken by a vault switch never lands`() =
        runTest {
            val queries = MutableStateFlow("plan")
            val vault = MutableStateFlow(1L)
            val landed = mutableListOf<String>()
            val job =
                launch {
                    queries
                        .searchSteps(
                            vault,
                            DEBOUNCE,
                            quick = { _, _ -> emptyList<String>() },
                            full = { query, vaultId ->
                                // The first vault's full-text pass is slow, and
                                // the switch arrives while it is still running.
                                if (vaultId == 1L) delay(SLOW)
                                answer(query, vaultId)
                            },
                        ).collect { step -> step.hits?.let(landed::addAll) }
                }

            advanceUntilIdle()
            landed.clear()
            vault.value = 1L
            queries.value = "notes"
            // Past the debounce, into the slow search, then switch.
            delay(DEBOUNCE + 1)
            vault.value = 2L
            advanceUntilIdle()

            assertThat(landed).containsExactly("2:notes")
            job.cancel()
        }

    private companion object {
        const val DEBOUNCE = 200L
        const val SLOW = 5_000L
    }
}
