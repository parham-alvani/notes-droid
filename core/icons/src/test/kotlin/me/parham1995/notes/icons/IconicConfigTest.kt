package me.parham1995.notes.icons

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fixtures here are synthetic. They reproduce the *shapes* the real plugin file
 * uses -- explicit entries for both files and folders, a path-prefix file rule,
 * a regex folder rule, named and RGB colours, emoji -- without carrying any
 * real vault path into a public repository.
 */
class IconicConfigTest {
    private fun config(body: String) = IconicConfig.parse(body)

    @Test
    fun `reads an explicit icon for a file`() {
        val subject = config("""{"fileIcons":{"Ledger/Rates.md":{"icon":"lucide-banknote"}}}""")

        assertThat(subject.forFile("Ledger/Rates.md")).isEqualTo(IconSpec.Glyph("banknote"))
    }

    @Test
    fun `strips the lucide prefix so the name matches the icon set`() {
        val subject = config("""{"fileIcons":{"a.md":{"icon":"lucide-piggy-bank"}}}""")

        assertThat((subject.forFile("a.md") as IconSpec.Glyph).name).isEqualTo("piggy-bank")
    }

    @Test
    fun `reads a named colour`() {
        val subject = config("""{"fileIcons":{"a.md":{"icon":"lucide-skull","color":"green"}}}""")

        assertThat(subject.forFile("a.md")?.color).isEqualTo(IconColor.Named("green"))
    }

    @Test
    fun `reads a colour picked from the wheel`() {
        val subject = config("""{"fileIcons":{"a.md":{"icon":"lucide-skull","color":{"rgb":16711680}}}}""")

        assertThat(subject.forFile("a.md")?.color).isEqualTo(IconColor.Rgb(0xFF0000))
    }

    @Test
    fun `treats a value with no ascii letters as an emoji`() {
        val subject =
            config(
                """{"fileIcons":{"a.md":{"icon":"🍉"},"b.md":{"icon":"🇮🇷"},"c.md":{"icon":"discord"}}}""",
            )

        assertThat(subject.forFile("a.md")).isEqualTo(IconSpec.Emoji("🍉"))
        assertThat(subject.forFile("b.md")).isEqualTo(IconSpec.Emoji("🇮🇷"))
        // Not every non-Lucide id is an emoji; Obsidian ships its own too.
        assertThat(subject.forFile("c.md")).isEqualTo(IconSpec.Glyph("discord"))
    }

    @Test
    fun `folders and files share one table of explicit entries`() {
        val subject = config("""{"fileIcons":{"Ledger":{"icon":"lucide-wallet"}}}""")

        assertThat(subject.forFolder("Ledger")).isEqualTo(IconSpec.Glyph("wallet"))
    }

    @Test
    fun `a file rule applies by path prefix`() {
        val subject =
            config(
                """
                {"fileRules":[{"id":"r1","name":"People","match":"all","enabled":true,
                "conditions":[{"source":"path","operator":"startsWith","value":"People/"}],
                "icon":"lucide-venetian-mask","color":"cyan"}]}
                """.trimIndent(),
            )

        assertThat(subject.forFile("People/Ada.md")).isEqualTo(IconSpec.Glyph("venetian-mask", IconColor.Named("cyan")))
        assertThat(subject.forFile("Notes/Ada.md")).isNull()
    }

    @Test
    fun `a folder rule matches a regex against the folder's own path`() {
        val subject = folderRule("""^Projects/[^/]*${'$'}""")

        assertThat(subject.forFolder("Projects/Apollo")).isEqualTo(IconSpec.Glyph("building"))
        // One level too deep, and the root itself, are both outside the rule.
        assertThat(subject.forFolder("Projects/Apollo/Notes")).isNull()
        assertThat(subject.forFolder("Projects")).isNull()
    }

    @Test
    fun `a folder rule does not colour the files inside the folder`() {
        val subject = folderRule("""^Projects/[^/]*${'$'}""")

        // The file's tree is `Projects/Apollo`, which the regex matches -- so a
        // rule list shared between files and folders would decorate every note
        // in the folder with the folder's own icon.
        assertThat(subject.forFile("Projects/Apollo/Plan.md")).isNull()
    }

    @Test
    fun `an explicit icon beats a rule that also selects the item`() {
        val subject =
            config(
                """
                {"fileIcons":{"Projects/Apollo":{"icon":"lucide-rocket"}},
                "folderRules":[{"id":"r1","name":"Projects","match":"all","enabled":true,
                "conditions":[{"source":"tree","operator":"startsWith","value":"Projects/"}],
                "icon":"lucide-building"}]}
                """.trimIndent(),
            )

        assertThat(subject.forFolder("Projects/Apollo")).isEqualTo(IconSpec.Glyph("rocket"))
    }

    @Test
    fun `a disabled rule is ignored`() {
        val subject =
            config(
                """
                {"fileRules":[{"id":"r1","name":"x","match":"all","enabled":false,
                "conditions":[{"source":"path","operator":"startsWith","value":"People/"}],
                "icon":"lucide-venetian-mask"}]}
                """.trimIndent(),
            )

        assertThat(subject.forFile("People/Ada.md")).isNull()
    }

    @Test
    fun `match all requires every condition and match any requires one`() {
        fun rule(mode: String) =
            config(
                """
                {"fileRules":[{"id":"r1","name":"x","match":"$mode","enabled":true,
                "conditions":[{"source":"extension","operator":"is","value":"md"},
                              {"source":"name","operator":"startsWith","value":"Draft"}],
                "icon":"lucide-file"}]}
                """.trimIndent(),
            )

        assertThat(rule("all").forFile("Notes/Draft one.md")).isNotNull()
        assertThat(rule("all").forFile("Notes/Final.md")).isNull()
        assertThat(rule("any").forFile("Notes/Final.md")).isNotNull()
        assertThat(rule("none").forFile("Notes/Final.md")).isNull()
        assertThat(rule("none").forFile("Notes/Final.txt")).isNotNull()
    }

    @Test
    fun `a negated operator inverts its test`() {
        val subject =
            config(
                """
                {"fileRules":[{"id":"r1","name":"x","match":"all","enabled":true,
                "conditions":[{"source":"extension","operator":"!is","value":"md"}],
                "icon":"lucide-file"}]}
                """.trimIndent(),
            )

        assertThat(subject.forFile("a.txt")).isNotNull()
        assertThat(subject.forFile("a.md")).isNull()
    }

    @Test
    fun `an unevaluable condition never matches instead of matching everything`() {
        // `property` needs frontmatter this app does not index. Matching on it
        // would put a wrong icon on every row; not matching loses one icon.
        val subject =
            config(
                """
                {"fileRules":[{"id":"r1","name":"x","match":"all","enabled":true,
                "conditions":[{"source":"property","operator":"is","value":"book"}],
                "icon":"lucide-book"}]}
                """.trimIndent(),
            )

        assertThat(subject.forFile("a.md")).isNull()
    }

    @Test
    fun `a malformed regex loses its rule rather than throwing`() {
        val subject = folderRule("[unclosed")

        assertThat(subject.forFolder("Projects/Apollo")).isNull()
    }

    @Test
    fun `paths are compared in NFC so a decomposed filename still matches`() {
        // The plugin writes composed; a filesystem may hand back decomposed.
        val composed = "Food/Çorba.md"
        val decomposed = "Food/Çorba.md"
        val subject = config("""{"fileIcons":{"$composed":{"icon":"lucide-soup"}}}""")

        assertThat(subject.forFile(decomposed)).isEqualTo(IconSpec.Glyph("soup"))
    }

    @Test
    fun `an entry with no icon is skipped`() {
        val subject = config("""{"fileIcons":{"a.md":{"color":"red"}}}""")

        assertThat(subject.forFile("a.md")).isNull()
    }

    @Test
    fun `a file with no assignment has no icon`() {
        // The vault sets showAllFileIcons off, so an unassigned row shows none
        // rather than a default page glyph.
        assertThat(config("""{"fileIcons":{}}""").forFile("a.md")).isNull()
        assertThat(IconicConfig.EMPTY.isEmpty).isTrue()
    }

    @Test
    fun `unknown top-level keys are ignored`() {
        val subject =
            config(
                """{"biggerIcons":"mobile","rememberDeletedItems":true,"fileIcons":{"a.md":{"icon":"lucide-star"}}}""",
            )

        assertThat(subject.forFile("a.md")).isEqualTo(IconSpec.Glyph("star"))
    }

    private fun folderRule(pattern: String) =
        config(
            """
            {"folderRules":[{"id":"r1","name":"Projects","match":"all","enabled":true,
            "conditions":[{"source":"tree","operator":"matches","value":"$pattern"}],
            "icon":"lucide-building"}]}
            """.trimIndent(),
        )
}
