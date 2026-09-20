package me.parham1995.notes.feature.drawer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Walking up out of a folder.
 *
 * One line of logic, and the one that decides whether the drawer can get back
 * to the root: `substringBeforeLast` with no separator returns the whole string
 * unless it is told what to fall back to, which would have left the up row
 * pointing at the folder it was already in.
 */
class FileDrawerStateTest {
    private fun parentOf(folder: String) = FileDrawerUiState(folder = folder).parent

    @Test
    fun `the root has nowhere above it`() {
        assertThat(parentOf("")).isNull()
    }

    @Test
    fun `a top-level folder goes back to the root`() {
        assertThat(parentOf("Learning")).isEqualTo("")
    }

    @Test
    fun `a nested folder drops one level at a time`() {
        assertThat(parentOf("Learning/Infrastructure-and-DevOps")).isEqualTo("Learning")
        assertThat(parentOf("Learning/Infrastructure-and-DevOps/IoT")).isEqualTo("Learning/Infrastructure-and-DevOps")
    }

    @Test
    fun `a query decides which half of the drawer is shown`() {
        assertThat(FileDrawerUiState(query = "  ").filtering).isFalse()
        assertThat(FileDrawerUiState(query = "fleet").filtering).isTrue()
    }
}
