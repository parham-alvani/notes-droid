package me.parham1995.notes.feature.drawer

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.VaultItem
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

    @Test
    fun `a tap on a file that is not a note opens the file`() {
        assertThat(drawerTap(VaultItem("Papers/Lease.pdf", "Lease.pdf", isFolder = false)))
            .isEqualTo(DrawerTap.File("Papers/Lease.pdf"))
        assertThat(drawerTap(VaultItem("Plants/Tomato.md", "Tomato", isFolder = false, noteId = 4)))
            .isEqualTo(DrawerTap.Note(4))
        // A folder with a landing page still opens as a folder; holding it opens the page.
        assertThat(drawerTap(VaultItem("Plants", "Plants", isFolder = true, noteId = 9)))
            .isEqualTo(DrawerTap.Folder("Plants"))
    }
}
