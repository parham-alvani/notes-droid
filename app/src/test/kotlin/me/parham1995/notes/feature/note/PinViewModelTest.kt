package me.parham1995.notes.feature.note

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.parham1995.notes.data.PinStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The menu's pin arrives at the store, for the note on screen and no other.
 *
 * [NoteMenuTest] shows the tap reaches `onTogglePin`; this is the other half,
 * that what the note screen hands it actually pins something.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PinViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PinStore(context)

    @Before
    fun setUp() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            store.pins.first().forEach { store.unpin(it.vaultId, it.path) }
        }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggling pins the tracked note, and toggling again unpins it`() =
        runTest {
            val model = PinViewModel(store)
            model.track(vaultId = 7, path = "Folder/Note.md")

            model.toggle()
            assertThat(store.isPinned(7, "Folder/Note.md").first { it }).isTrue()
            // The same path in another vault is another note.
            assertThat(store.isPinned(8, "Folder/Note.md").first()).isFalse()

            model.toggle()
            assertThat(store.isPinned(7, "Folder/Note.md").first { !it }).isFalse()
        }

    @Test
    fun `with no note on screen there is nothing to pin`() =
        runTest {
            PinViewModel(store).toggle()

            assertThat(store.pins.first()).isEmpty()
        }
}
