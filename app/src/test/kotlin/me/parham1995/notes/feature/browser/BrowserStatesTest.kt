package me.parham1995.notes.feature.browser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.VaultEntity
import org.junit.Test

/**
 * That the crash banner follows the crash flag.
 *
 * The flag was read while building the state rather than being one of its
 * inputs, so the banner appeared only if the database happened to change after
 * the crash log was read, and Dismiss did nothing until it changed again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserStatesTest {
    @Test
    fun `the banner appears when the crash is found and goes when it is dismissed`() =
        runTest(UnconfinedTestDispatcher()) {
            val crashed = MutableStateFlow(false)
            var shown: BrowserUiState? = null
            val job =
                browserStates(
                    path = flowOf(""),
                    rows = flowOf(emptyList()),
                    recent = flowOf(emptyList()),
                    noteCount = flowOf(3),
                    vaults = flowOf(emptyList<VaultEntity>() to 1L),
                    crashed = crashed,
                ).onEach { shown = it }.launchIn(this)

            assertThat(shown?.crashed).isFalse()

            // The crash log is read after the database has already answered,
            // and nothing else changes after it.
            crashed.value = true
            assertThat(shown?.crashed).isTrue()

            crashed.value = false
            assertThat(shown?.crashed).isFalse()
            job.cancel()
        }
}
