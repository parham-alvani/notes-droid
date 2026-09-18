package me.parham1995.notes.data

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.icons.IconicConfig
import org.junit.Test

/**
 * Mounting is the whole of what a second repository added, so it is the whole
 * of what can go wrong. The root-mounted case matters most: it is what every
 * install that existed before this already has, and it has to stay the
 * identity in both directions.
 */
class VaultMountTest {
    private val root = VaultEntity(id = 1, owner = "someone", repo = "notes", mount = "")
    private val work = VaultEntity(id = 2, owner = "someone", repo = "work", mount = "Work")

    @Test
    fun `a root-mounted repository changes no path at all`() {
        assertThat(root.mounted("Ledger/Rates.md")).isEqualTo("Ledger/Rates.md")
        assertThat(root.unmounted("Ledger/Rates.md")).isEqualTo("Ledger/Rates.md")
    }

    @Test
    fun `a mounted repository moves under its folder and back again`() {
        assertThat(work.mounted("Ledger/Rates.md")).isEqualTo("Work/Ledger/Rates.md")
        assertThat(work.unmounted("Work/Ledger/Rates.md")).isEqualTo("Ledger/Rates.md")
    }

    @Test
    fun `mounting and unmounting round-trip for every vault`() {
        listOf(root, work).forEach { vault ->
            val path = "Notes/Deep/One.md"
            assertThat(vault.unmounted(vault.mounted(path))).isEqualTo(path)
        }
    }

    @Test
    fun `a repository owns its own folder and nothing else`() {
        assertThat(work.owns("Work/a.md")).isTrue()
        assertThat(work.owns("Work")).isTrue()
        assertThat(work.owns("Workshop/a.md")).isFalse()
        assertThat(work.owns("Other/a.md")).isFalse()
        // The root owns everything, which is what makes it the fallback.
        assertThat(root.owns("anything/at/all.md")).isTrue()
    }

    @Test
    fun `each repository's icons answer for its own paths`() {
        val icons =
            VaultIcons(
                listOf(
                    "" to IconicConfig.parse("""{"fileIcons":{"Ledger":{"icon":"lucide-wallet"}}}"""),
                    "Work" to IconicConfig.parse("""{"fileIcons":{"Ledger":{"icon":"lucide-briefcase"}}}"""),
                ),
            )

        // The same relative path in two repositories, and each keeps its own.
        assertThat(icons.forPath("Ledger", isFolder = true)).isEqualTo(IconSpec.Glyph("wallet"))
        assertThat(icons.forPath("Work/Ledger", isFolder = true)).isEqualTo(IconSpec.Glyph("briefcase"))
    }

    @Test
    fun `the longest matching mount wins, not the first`() {
        val icons =
            VaultIcons(
                listOf(
                    "" to IconicConfig.parse("""{"fileIcons":{"Work/a.md":{"icon":"lucide-star"}}}"""),
                    "Work" to IconicConfig.parse("""{"fileIcons":{"a.md":{"icon":"lucide-heart"}}}"""),
                ),
            )

        // The root config also has an entry that matches by path. The mounted
        // repository is the more specific owner and answers for it.
        assertThat(icons.forFile("Work/a.md")).isEqualTo(IconSpec.Glyph("heart"))
    }

    @Test
    fun `a path no repository claims has no icon`() {
        val icons =
            VaultIcons(listOf("Work" to IconicConfig.parse("""{"fileIcons":{"a.md":{"icon":"lucide-star"}}}""")))

        assertThat(icons.forFile("Elsewhere/a.md")).isNull()
        assertThat(VaultIcons.EMPTY.forFile("anything.md")).isNull()
        assertThat(VaultIcons.EMPTY.isEmpty).isTrue()
    }
}
