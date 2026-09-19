package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What is written down so the tabs come back.
 *
 * Whatever this produces is read again after a restart, possibly much later
 * and possibly after the notes were reindexed, so it has to survive nonsense
 * rather than throw on it.
 */
class TabsCodecTest {
    private val tabs =
        StoredTabs(
            tabs =
                listOf(
                    StoredTab(listOf(TabRef(1, "Learning/CQRS.md"), TabRef(1, "Spain/Renting in Barcelona.md")), 1),
                    StoredTab(listOf(TabRef(2, "identities/passport.md")), 0),
                ),
            active = 1,
        )

    @Test
    fun `what is written is what comes back`() {
        assertThat(TabsCodec.decode(TabsCodec.encode(tabs))).isEqualTo(tabs)
    }

    @Test
    fun `a path with spaces and punctuation survives`() {
        // Real shapes from this vault: spaces, an ampersand, a hash, Persian.
        val awkward =
            StoredTabs(
                listOf(StoredTab(listOf(TabRef(1, "Diet/Cop sis & \u062f\u06a9\u062a\u0631 #1.md")), 0)),
                0,
            )
        assertThat(TabsCodec.decode(TabsCodec.encode(awkward))).isEqualTo(awkward)
    }

    @Test
    fun `nothing written down is nothing to restore`() {
        assertThat(TabsCodec.decode(null)).isNull()
        assertThat(TabsCodec.decode("")).isNull()
    }

    @Test
    fun `garbage is nothing to restore, not a crash`() {
        assertThat(TabsCodec.decode("not a number")).isNull()
        assertThat(TabsCodec.decode("0")).isNull()
    }

    @Test
    fun `an index past the end of its own trail is pulled back into it`() {
        val one = StoredTabs(listOf(StoredTab(listOf(TabRef(1, "a.md")), 0)), 0)
        val broken = TabsCodec.encode(one).replace("\u001D0", "\u001D9")
        assertThat(
            TabsCodec
                .decode(broken)
                ?.tabs
                ?.single()
                ?.index,
        ).isEqualTo(0)
    }

    @Test
    fun `an empty tab is dropped rather than restored as a blank`() {
        val text = TabsCodec.encode(tabs) + "\u001D0"
        assertThat(TabsCodec.decode(text)?.tabs).hasSize(2)
    }
}
