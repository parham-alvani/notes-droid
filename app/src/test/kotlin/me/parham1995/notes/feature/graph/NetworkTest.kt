package me.parham1995.notes.feature.graph

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.NoteGraph
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every fixture here is made up. The repository is public; the vault is not. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NetworkTest {
    @get:Rule
    val compose = createComposeRule()

    private val graph =
        NoteGraph.of(
            ids = listOf(1L, 2L, 3L, 4L, 5L, 6L),
            paths = listOf("Shops/A.md", "Shops/B.md", "People/C.md", "People/D.md", "Root.md", "Shops/F.md"),
            links = listOf(1L to 2L, 2L to 3L, 3L to 4L, 4L to 5L, 1L to 6L),
        )

    @Test
    fun `a depth keeps the notes that many hops out, the centre first`() {
        val two = network(graph, centre = 0, reach = GraphReach.Hops(2))
        assertThat(two.nodes.map { it.id }).containsExactly(1L, 2L, 6L, 3L)
        assertThat(two.nodes.first().id).isEqualTo(1L)
        assertThat(two.centre).isEqualTo(0)
        // Pinned in the middle.
        assertThat(two.nodes.first().x).isEqualTo(0f)
    }

    @Test
    fun `the whole vault colours its top folders, biggest first`() {
        val all = network(graph, centre = 2, reach = GraphReach.Vault)
        assertThat(all.nodes).hasSize(6)
        assertThat(all.folders).containsExactly("Shops", "People").inOrder()
        assertThat(all.nodes.map { it.group }).containsExactly(0, 0, 1, 1, -1, 0).inOrder()
        assertThat(all.nodes.map { it.degree }).containsExactly(2, 2, 2, 2, 1, 1).inOrder()
        assertThat(all.centre).isEqualTo(2)
    }

    @Test
    fun `a tap on a dot opens its note`() {
        // The centre is pinned at the middle of the picture.
        val network = network(graph, centre = 0, reach = GraphReach.Hops(4))
        val opened = mutableListOf<Long>()
        compose.setContent { NetworkView(network, onOpenNote = { opened += it }) }

        compose.onNodeWithTag("network").performTouchInput { click(center) }

        assertThat(opened).containsExactly(1L)
    }
}
