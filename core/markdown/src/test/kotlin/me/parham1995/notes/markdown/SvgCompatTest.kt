package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Hand-built SVG figures that a browser draws and AndroidSVG does not.
 *
 * Every figure with an `auto-start-reverse` arrow showed as its caption and
 * nothing else, and every figure with a dark-mode `@media` block lost the CSS
 * after it. The fixture is synthetic but shaped like the real ones.
 */
class SvgCompatTest {
    private val figure =
        """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50" width="100%"><style>
        .t{fill:#74736f}
        @media (prefers-color-scheme:dark){.t{fill:#c3c2b7}}
        .n{fill:#f4f3ef}
        </style><defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z"/></marker></defs>
        <path d="M10,10 L90,10" marker-end="url(#arrow)"/>
        <path d="M10,30 L90,30" marker-start="url(#arrow)" marker-end="url(#arrow)"/>
        </svg>
        """.trimIndent()

    @Test
    fun `no marker is left with an orient AndroidSVG cannot read`() {
        val out = SvgCompat.rewrite(figure, dark = false)
        assertThat(out).doesNotContain("auto-start-reverse")
        assertThat(
            out,
        ).contains(
            """<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">""",
        )
    }

    @Test
    fun `a start marker points back, through a copy turned inside its own box`() {
        val out = SvgCompat.rewrite(figure, dark = false)
        // Turned about the box's middle (5, 5), so the head stays inside the
        // 0..10 box a marker is clipped to; the tip, at 9 before, is now at 1.
        assertThat(out).contains(
            """<marker id="arrow-start-reverse" viewBox="0 0 10 10" refX="1" refY="5" """ +
                """markerWidth="7" markerHeight="7" orient="auto">""" +
                """<g transform="rotate(180 5 5)"><path d="M0,0 L10,5 L0,10 z"/></g></marker></defs>""",
        )
        assertThat(out).contains("""marker-start="url(#arrow-start-reverse)" marker-end="url(#arrow)"""")
        assertThat(out).contains("""<path d="M10,10 L90,10" marker-end="url(#arrow)"/>""")
    }

    @Test
    fun `a dark page keeps the dark rules and the rules after them`() {
        val out = SvgCompat.rewrite(figure, dark = true)
        assertThat(out).doesNotContain("@media")
        assertThat(out).contains(".t{fill:#74736f}\n.t{fill:#c3c2b7}\n.n{fill:#f4f3ef}")
    }

    @Test
    fun `a light page drops the dark rules and keeps the rules after them`() {
        val out = SvgCompat.rewrite(figure, dark = false)
        assertThat(out).doesNotContain("@media")
        assertThat(out).doesNotContain("#c3c2b7")
        assertThat(out).contains(".t{fill:#74736f}\n\n.n{fill:#f4f3ef}")
    }

    @Test
    fun `media queries are read the way a browser reads them`() {
        fun kept(
            prelude: String,
            dark: Boolean,
        ) = SvgCompat.rewrite("<style>@media $prelude{.x{fill:red}}</style>", dark).contains(".x{fill:red}")

        assertThat(kept("screen and (prefers-color-scheme: dark)", dark = true)).isTrue()
        assertThat(kept("(prefers-color-scheme: light)", dark = true)).isFalse()
        assertThat(kept("(prefers-color-scheme: light)", dark = false)).isTrue()
        assertThat(kept("print and (prefers-color-scheme: dark)", dark = true)).isFalse()
        assertThat(kept("(min-width: 600px)", dark = true)).isFalse()
        assertThat(kept("(min-width: 600px), (prefers-color-scheme: dark)", dark = true)).isTrue()
    }

    @Test
    fun `a plain media type list is left for AndroidSVG`() {
        val svg = "<style>@media screen{.x{fill:red}}</style>"
        assertThat(SvgCompat.rewrite(svg, dark = true)).isEqualTo(svg)
    }

    @Test
    fun `an SVG with neither is returned as it came`() {
        val svg = """<svg viewBox="0 0 1 1"><marker id="a" orient="auto"/><path marker-start="url(#a)"/></svg>"""
        assertThat(SvgCompat.rewrite(svg, dark = true)).isSameInstanceAs(svg)
    }

    @Test
    fun `a copy's id never collides with one already in the file`() {
        val svg =
            """<defs><marker id="a" refX="1" orient="auto-start-reverse"><path/></marker>""" +
                """<marker id="a-start-reverse" orient="auto"/></defs><path marker-start="url(#a)"/>"""
        val out = SvgCompat.rewrite(svg, dark = false)
        assertThat(out).contains("""<marker id="a-start-reverse-2" """)
        assertThat(out).contains("""marker-start="url(#a-start-reverse-2)"""")
    }

    @Test
    fun `a marker with no viewBox turns inside its default 3 by 3 box`() {
        val svg =
            """<marker id="a" refX="1" orient="auto-start-reverse"><path/></marker>""" +
                """<path marker-start="url(#a)"/>"""
        assertThat(SvgCompat.rewrite(svg, dark = false)).contains(
            """<marker id="a-start-reverse" refX="2" orient="auto" refY="3"><g transform="rotate(180 1.5 1.5)"><path/></g></marker>""",
        )
    }
}
