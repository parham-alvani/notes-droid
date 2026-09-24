package me.parham1995.notes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins are read back through the same DataStore the app writes, because the
 * failure worth catching is one that compiles: a pin that is written and never
 * read, or read from the wrong vault.
 */
@RunWith(RobolectricTestRunner::class)
class PinStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PinStore(context)

    @Before
    fun clearPreferences() =
        runTest {
            context.settingsDataStore.edit { it.clear() }
        }

    @Test
    fun `nothing is pinned to begin with`() =
        runTest {
            assertThat(store.pins.first()).isEmpty()
            assertThat(store.isPinned(1, "Note.md").first()).isFalse()
        }

    @Test
    fun `the newest pin comes first`() =
        runTest {
            store.pin(1, "A.md")
            store.pin(1, "B.md")
            store.pin(1, "C.md")

            assertThat(store.pins.first().map { it.path }).containsExactly("C.md", "B.md", "A.md").inOrder()
        }

    @Test
    fun `pinning again moves it to the front instead of adding it twice`() =
        runTest {
            store.pin(1, "A.md")
            store.pin(1, "B.md")
            store.pin(1, "A.md")

            assertThat(store.pins.first()).containsExactly(Pin(1, "A.md"), Pin(1, "B.md")).inOrder()
        }

    @Test
    fun `unpinning takes out that one and no other`() =
        runTest {
            store.pin(1, "A.md")
            store.pin(1, "B.md")

            store.unpin(1, "A.md")

            assertThat(store.pins.first()).containsExactly(Pin(1, "B.md"))
            assertThat(store.isPinned(1, "A.md").first()).isFalse()
        }

    @Test
    fun `toggling pins, then unpins, and says which`() =
        runTest {
            assertThat(store.toggle(1, "A.md")).isTrue()
            assertThat(store.isPinned(1, "A.md").first()).isTrue()

            assertThat(store.toggle(1, "A.md")).isFalse()
            assertThat(store.isPinned(1, "A.md").first()).isFalse()
        }

    @Test
    fun `two vaults can each pin a note at the same path`() =
        runTest {
            store.pin(1, "README.md")
            store.pin(2, "README.md")

            assertThat(store.inVault(1).first()).containsExactly(Pin(1, "README.md"))
            assertThat(store.inVault(2).first()).containsExactly(Pin(2, "README.md"))

            // Unpinning one vault's is not unpinning the other's.
            store.unpin(1, "README.md")
            assertThat(store.isPinned(1, "README.md").first()).isFalse()
            assertThat(store.isPinned(2, "README.md").first()).isTrue()
        }

    @Test
    fun `forgetting drops exactly the pins named`() =
        runTest {
            store.pin(1, "Gone.md")
            store.pin(1, "Kept.md")
            store.pin(2, "Gone.md")

            store.forget(listOf(Pin(1, "Gone.md")))

            assertThat(store.pins.first()).containsExactly(Pin(2, "Gone.md"), Pin(1, "Kept.md")).inOrder()
        }

    @Test
    fun `a path with tabs and spaces and Persian in it comes back as written`() =
        runTest {
            val path = "یادداشت‌ها/Plans\tand notes/سلام دنیا.md"
            store.pin(3, path)

            assertThat(store.pins.first()).containsExactly(Pin(3, path))
        }

    @Test
    fun `a line that does not parse is dropped, not the whole list`() {
        assertThat(PinCodec.decode("1\tA.md\nnonsense\n\tB.md\nx\tC.md\n2\tD.md"))
            .containsExactly(Pin(1, "A.md"), Pin(2, "D.md"))
            .inOrder()
    }
}
