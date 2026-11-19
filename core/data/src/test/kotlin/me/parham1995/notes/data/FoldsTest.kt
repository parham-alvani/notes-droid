package me.parham1995.notes.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A note's folds as the `notes.folds` column holds them
 * (`folded-sections.md` in notes-linux's `docs/specs/`).
 */
class FoldsTest {
    @Test
    fun `no list kept is not an empty list kept`() {
        // What every note had before, and what a note never folded has.
        assertThat(Folds.parse("")).isNull()
        assertThat(Folds.parse(Folds.NONE_SHUT)).isEmpty()
        assertThat(Folds.parse("2,6")).containsExactly(2, 6)
    }

    @Test
    fun `none shut is kept only when it is a choice`() {
        assertThat(Folds.format(emptySet())).isEmpty()
        assertThat(Folds.format(emptySet(), explicit = true)).isEqualTo(Folds.NONE_SHUT)
        // Some shut is a list either way.
        assertThat(Folds.format(setOf(6, 2))).isEqualTo("2,6")
        assertThat(Folds.format(setOf(6, 2), explicit = true)).isEqualTo("2,6")
    }

    @Test
    fun `what is written is what is read`() {
        assertThat(Folds.parse(Folds.format(emptySet()))).isNull()
        assertThat(Folds.parse(Folds.format(emptySet(), explicit = true))).isEmpty()
        assertThat(Folds.parse(Folds.format(setOf(0, 4)))).containsExactly(0, 4)
    }

    @Test
    fun `a column that holds something else folds nothing`() {
        assertThat(Folds.parse("x, -3, ,")).isEmpty()
    }

    @Test
    fun `the app's word and the vault's decide together`() {
        assertThat(FoldSections.parse(null)).isEqualTo(FoldSections.VAULT)
        assertThat(FoldSections.parse("sometimes")).isEqualTo(FoldSections.VAULT)
        assertThat(FoldSections.parse("ALWAYS")).isEqualTo(FoldSections.ALWAYS)
        assertThat(FoldSections.parse("NEVER")).isEqualTo(FoldSections.NEVER)

        assertThat(FoldSections.VAULT.folds(vault = true)).isTrue()
        assertThat(FoldSections.VAULT.folds(vault = false)).isFalse()
        assertThat(FoldSections.ALWAYS.folds(vault = false)).isTrue()
        assertThat(FoldSections.NEVER.folds(vault = true)).isFalse()
    }
}
