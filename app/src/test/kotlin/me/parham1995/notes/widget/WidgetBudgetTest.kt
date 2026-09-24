package me.parham1995.notes.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * How long a widget may hold its broadcast open.
 *
 * Holding it with `goAsync` until the database answered was right, and the
 * first launch after an upgrade -- migrations running, the database busy --
 * took longer than the ten seconds a foreground broadcast is given. The system
 * called the whole app unresponsive over a widget. Found on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetBudgetTest {
    @Test
    fun `a draw that never ends gives up inside the broadcast's time`() =
        runTest {
            withinBroadcastBudget { awaitCancellation() }

            assertThat(currentTime).isAtMost(BROADCAST_BUDGET_MS)
            assertThat(BROADCAST_BUDGET_MS).isLessThan(FOREGROUND_BROADCAST_TIMEOUT_MS)
        }

    @Test
    fun `a draw that finishes is not cut short`() =
        runTest {
            var drew = false
            withinBroadcastBudget { drew = true }

            assertThat(drew).isTrue()
        }

    private companion object {
        /** What Android gives a broadcast delivered to a foreground app. */
        const val FOREGROUND_BROADCAST_TIMEOUT_MS = 10_000L
    }
}
