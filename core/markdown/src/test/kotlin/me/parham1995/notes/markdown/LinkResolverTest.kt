package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinkResolverTest {
    // Synthetic on purpose: this repository is public, and real vault paths
    // would disclose the folder names.
    private val paths =
        listOf(
            "Alpha/Alpha.md",
            "Alpha/Beta/Beta.md",
            "Alpha/Beta/Topic.md",
            "Alpha/Gamma/Topic.md",
            "Alpha/Gamma/Gamma.md",
            "Delta/Notes/Topic.md",
            "Delta/Standalone.md",
            "Root.md",
            "Alpha/Beta/Deep/Nested Note.md",
            "Alpha/Mixed/PgBouncer.md",
            "Alpha/Mixed/Postgresql.md",
        )
    private val resolver = LinkResolver(paths)

    @Test
    fun `a bare unique name resolves`() {
        assertThat(resolver.resolve("Standalone", "Root.md")).isEqualTo("Delta/Standalone.md")
        assertThat(resolver.resolve("Nested Note", "Root.md")).isEqualTo("Alpha/Beta/Deep/Nested Note.md")
    }

    @Test
    fun `a partial path resolves as a suffix, from anywhere`() {
        // The general rule: any trailing slice of the real path matches.
        assertThat(resolver.resolve("Beta/Topic", "Root.md")).isEqualTo("Alpha/Beta/Topic.md")
        assertThat(resolver.resolve("Alpha/Gamma/Topic", "Root.md")).isEqualTo("Alpha/Gamma/Topic.md")
        assertThat(resolver.resolve("Deep/Nested Note", "Root.md")).isEqualTo("Alpha/Beta/Deep/Nested Note.md")
    }

    @Test
    fun `an explicit md extension is accepted`() {
        assertThat(resolver.resolve("Delta/Standalone.md", "Root.md")).isEqualTo("Delta/Standalone.md")
    }

    @Test
    fun `a folder resolves to its folder note`() {
        // How an index note links to its children.
        assertThat(resolver.resolve("Alpha/Beta", "Root.md")).isEqualTo("Alpha/Beta/Beta.md")
        assertThat(resolver.resolve("Alpha", "Root.md")).isEqualTo("Alpha/Alpha.md")
    }

    @Test
    fun `an ambiguous name prefers the nearest source`() {
        // Topic exists three times. Each caller should land in its own tree.
        assertThat(resolver.resolve("Topic", "Alpha/Beta/Beta.md")).isEqualTo("Alpha/Beta/Topic.md")
        assertThat(resolver.resolve("Topic", "Alpha/Gamma/Gamma.md")).isEqualTo("Alpha/Gamma/Topic.md")
        assertThat(resolver.resolve("Topic", "Delta/Standalone.md")).isEqualTo("Delta/Notes/Topic.md")
    }

    @Test
    fun `an unresolvable ambiguity is at least stable`() {
        // From a source sharing nothing, any answer is a guess -- but it must
        // be the same guess every time, or backlinks flicker between syncs.
        val first = resolver.resolve("Topic", "Root.md")
        val second = resolver.resolve("Topic", "Root.md")
        assertThat(first).isEqualTo(second)
        assertThat(first).isIn(paths)
    }

    @Test
    fun `case-only mismatches still resolve`() {
        // These work on a case-insensitive filesystem and would silently break
        // on Android without the folded fallback.
        assertThat(resolver.resolve("pgBouncer", "Root.md")).isEqualTo("Alpha/Mixed/PgBouncer.md")
        assertThat(resolver.resolve("Alpha/Mixed/PostgreSQL", "Root.md")).isEqualTo("Alpha/Mixed/Postgresql.md")
    }

    @Test
    fun `a trailing slash is not part of the name`() {
        assertThat(resolver.resolve("Delta/Standalone/", "Root.md")).isEqualTo("Delta/Standalone.md")
    }

    @Test
    fun `an empty target means this same note`() {
        assertThat(resolver.resolve("", "Alpha/Beta/Topic.md")).isEqualTo("Alpha/Beta/Topic.md")
    }

    @Test
    fun `a target with no match is broken`() {
        assertThat(resolver.resolve("Does Not Exist", "Root.md")).isNull()
        assertThat(resolver.resolve("Alpha/Wrong/Topic", "Root.md")).isNull()
    }

    @Test
    fun `unicode filenames survive normalisation`() {
        val unicode = LinkResolver(listOf("Food/Iskender kebap.md", "Food/Cop sis.md"))
        assertThat(unicode.resolve("Iskender kebap", "Root.md")).isEqualTo("Food/Iskender kebap.md")
        assertThat(unicode.resolve("iskender kebap", "Root.md")).isEqualTo("Food/Iskender kebap.md")
    }
}
