package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A Markdown link resolved as the Open Knowledge Format says
 * (`open-knowledge-format.md` in notes-linux's `docs/specs/`, section 3), by
 * the test name the spec gives.
 */
class OkfLinksTest {
    private val paths =
        listOf(
            "Docs/index.md",
            "Docs/tables/orders.md",
            "Docs/metrics/revenue.md",
            "Docs/metrics/my note.md",
            "Other/x.md",
            "Other/orders.md",
            "Docs/tables/index.md",
        )

    /** `Docs` is a bundle: its `index.md` has `okf_version`. */
    private val resolver = LinkResolver(paths, bundleRoots = listOf("Docs"))

    /** Where `[text](destination)` goes from [source], as a tap and the index both find it. */
    private fun resolve(
        destination: String,
        source: String,
    ): Pair<String?, String?> {
        val link = MarkdownLinks.internal(destination)
        return resolver.resolve(link.target, source) to link.heading
    }

    @Test
    fun aMarkdownLinkResolvesAsOKFSays() {
        val revenue = "Docs/metrics/revenue.md"
        assertThat(Okf.isBundleRoot("---\nokf_version: \"0.3\"\n---\n# Docs\n")).isTrue()
        assertThat(Okf.isBundleRoot("---\ntitle: Docs\n---\n")).isFalse()
        assertThat(Okf.isBundleRoot("# Docs\n")).isFalse()

        // A `/` path is from the bundle's root.
        assertThat(resolve("/tables/orders.md", revenue).first).isEqualTo("Docs/tables/orders.md")
        // Any other is from the note's folder.
        assertThat(resolve("../tables/orders.md", revenue).first).isEqualTo("Docs/tables/orders.md")
        assertThat(resolve("./revenue.md#Definition", revenue)).isEqualTo(revenue to "Definition")
        assertThat(resolve("my%20note.md", revenue).first).isEqualTo("Docs/metrics/my note.md")
        // The file beside the note, not the one of the same name elsewhere.
        assertThat(resolve("orders.md", "Other/x.md").first).isEqualTo("Other/orders.md")

        // A folder is its `index.md`, and a path without `.md` is tried with it.
        assertThat(resolve("/tables/", revenue).first).isEqualTo("Docs/tables/index.md")
        assertThat(resolve("../tables/", revenue).first).isEqualTo("Docs/tables/index.md")
        assertThat(resolve("/tables/orders", revenue).first).isEqualTo("Docs/tables/orders.md")
        assertThat(resolve("../tables/orders", revenue).first).isEqualTo("Docs/tables/orders.md")
        // A relative path may leave the bundle, never the vault.
        assertThat(resolve("../../Other/x.md", revenue).first).isEqualTo("Other/x.md")

        // Under no bundle, a `/` path is from the vault's root.
        assertThat(resolve("/Docs/tables/orders.md", "Other/x.md").first).isEqualTo("Docs/tables/orders.md")

        // Out of the vault, or out of the bundle: nothing, and nothing is looked for.
        assertThat(resolve("../../../etc/passwd", revenue).first).isNull()
        assertThat(resolve("/../x.md", revenue).first).isNull()
        assertThat(resolve("/../Other/x.md", revenue).first).isNull()
        assertThat(resolve("/../../etc/passwd", "Other/x.md").first).isNull()
    }

    @Test
    fun `a plain name that is no file beside the note is found as a wikilink is, and a path is only what it says`() {
        val plain = LinkResolver(paths)

        // `[x](revenue.md)` and `[x](metrics/revenue)` for a note elsewhere, as before OKF's rules.
        assertThat(plain.resolve(MarkdownLinks.internal("revenue.md").target, "Other/x.md"))
            .isEqualTo("Docs/metrics/revenue.md")
        assertThat(plain.resolve(MarkdownLinks.internal("metrics/revenue").target, "Other/x.md"))
            .isEqualTo("Docs/metrics/revenue.md")
        // A `/`, `./` or `../` path does not fall back.
        assertThat(plain.resolve(MarkdownLinks.internal("/tables/orders.md").target, "Other/x.md")).isNull()
        assertThat(plain.resolve(MarkdownLinks.internal("./revenue.md").target, "Other/x.md")).isNull()
        assertThat(plain.resolve(MarkdownLinks.internal("../revenue.md").target, "Other/x.md")).isNull()
        // And a wikilink is read as it always was.
        assertThat(plain.resolve("revenue", "Other/x.md")).isEqualTo("Docs/metrics/revenue.md")
    }
}
