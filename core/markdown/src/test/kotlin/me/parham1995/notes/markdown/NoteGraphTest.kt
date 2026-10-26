package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

/** Every fixture here is made up. The repository is public; the vault is not. */
class NoteGraphTest {
    private val graph =
        NoteGraph.of(
            ids = listOf(10L, 11L, 12L, 13L, 14L),
            paths = listOf("Home.md", "Shops/Bakery.md", "Shops/Cafe.md", "People/Ana.md", "Lonely.md"),
            links =
                listOf(
                    10L to 11L,
                    11L to 10L,
                    11L to 12L,
                    12L to 13L,
                    // Itself, and a note in no list: both left out.
                    13L to 13L,
                    13L to 99L,
                ),
        )

    @Test
    fun `a link both ways is one edge, and a link to nowhere is none`() {
        assertThat(graph.edges).containsExactly(0 to 1, 1 to 2, 2 to 3)
        assertThat(graph.degrees.toList()).containsExactly(1, 2, 2, 1, 0).inOrder()
    }

    @Test
    fun `depth counts links either way`() {
        assertThat(graph.within(0, 1)).containsExactly(0, 1)
        assertThat(graph.within(0, 2)).containsExactly(0, 1, 2)
        assertThat(graph.within(3, 4)).containsExactly(3, 2, 1, 0)
        assertThat(graph.within(4, 4)).containsExactly(4)
    }

    @Test
    fun `a part of the graph keeps only its own links`() {
        val part = graph.only(listOf(1, 2, 4))
        assertThat(part.ids).containsExactly(11L, 12L, 14L).inOrder()
        assertThat(part.edges).containsExactly(0 to 1)
        assertThat(part.folderOf(0)).isEqualTo("Shops")
        assertThat(graph.folderOf(0)).isEmpty()
    }

    @Test
    fun `the layout pulls linked notes closer than unlinked ones, and is the same every time`() {
        // Two clusters of five, joined by nothing.
        val edges = (0 until 4).map { it to it + 1 } + (5 until 9).map { it to it + 1 }
        val xy = ForceLayout.layout(10, edges, groups = IntArray(10) { if (it < 5) 0 else 1 })

        fun distance(
            a: Int,
            b: Int,
        ) = hypot((xy[a * 2] - xy[b * 2]).toDouble(), (xy[a * 2 + 1] - xy[b * 2 + 1]).toDouble())

        assertThat(distance(0, 1)).isLessThan(distance(0, 9))
        assertThat(xy.all { it in -1f..1f }).isTrue()
        assertThat(ForceLayout.layout(10, edges, groups = IntArray(10) { if (it < 5) 0 else 1 }).toList())
            .isEqualTo(xy.toList())
    }

    @Test
    fun `a few thousand notes lay out quickly`() {
        val count = 3000
        val edges =
            (1 until count).map { (it * 7919) % it to it } + (0 until count step 3).map { it to (it + 1) % count }
        val started = System.nanoTime()
        val xy = ForceLayout.layout(count, edges, groups = IntArray(count) { it % 8 })
        val seconds = (System.nanoTime() - started) / 1e9
        assertThat(xy.size).isEqualTo(count * 2)
        assertThat(xy.none { it.isNaN() }).isTrue()
        // Generous for a CI machine; a phone is slower than a laptop, and this
        // runs off the main thread there.
        assertThat(seconds).isLessThan(10.0)
    }

    @Test
    fun `a pinned note stays in the middle`() {
        val xy = ForceLayout.layout(4, listOf(0 to 1, 1 to 2, 2 to 3), pinned = 2)
        assertThat(xy[4]).isEqualTo(0f)
        assertThat(xy[5]).isEqualTo(0f)
    }
}
