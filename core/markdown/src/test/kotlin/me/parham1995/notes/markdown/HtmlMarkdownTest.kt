package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class HtmlMarkdownTest {
    @Test
    fun `a web page becomes markdown`() {
        val html =
            """
            <html><head><style>p{}</style></head><body>
            <h2>Getting <em>there</em></h2>
            <p>Take the <strong>red</strong> line to <a href="https://example.com/a b">the park</a>.<br>Then walk.</p>
            <ul><li>One</li><li>Two<ul><li>Two and a half</li></ul></li></ul>
            <ol start="3"><li><p>Third</p></li></ol>
            <blockquote><p>Quoted</p></blockquote>
            <pre><code class="language-swift">let a = 1
            let b = 2</code></pre>
            <table><tr><th>Name</th><th>Age</th></tr><tr><td>Ana</td><td>30</td></tr></table>
            <p><img src="https://example.com/x.png" alt="X"> and <code>inline</code></p>
            </body></html>
            """.trimIndent()

        assertThat(HtmlMarkdown.convert(html)).isEqualTo(
            """
            ## Getting *there*

            Take the **red** line to [the park](https://example.com/a%20b).
            Then walk.

            - One
            - Two
                - Two and a half

            3. Third

            > Quoted

            ```swift
            let a = 1
            let b = 2
            ```

            | Name | Age |
            | --- | --- |
            | Ana | 30 |

            ![X](https://example.com/x.png) and `inline`
            """.trimIndent(),
        )
    }

    @Test
    fun `code editors and plain text stay plain`() {
        val editor =
            """<meta charset="utf-8"><div style="color: #d4d4d4;"><div><span style="color: #569cd6;">let</span> a = 1</div></div>"""

        assertThat(HtmlMarkdown.convert(editor)).isNull()
        assertThat(HtmlMarkdown.convert("just words")).isNull()
        assertThat(HtmlMarkdown.convert("")).isNull()
    }

    @Test
    fun `google docs bold spans count`() {
        val html =
            """<b style="font-weight:normal;" id="docs"><p><span style="font-weight:700;">Bold</span> and """ +
                """<span style="font-style:italic;">slanted</span></p></b>"""

        assertThat(HtmlMarkdown.convert(html)).isEqualTo("**Bold** and *slanted*")
    }

    @Test
    fun `entities are read, and a mark hugs its words`() {
        val html = "<p>Fish &amp; chips&nbsp;&mdash; <b> crisp </b>&#33; &#x200c;&unknown;</p>"

        assertThat(HtmlMarkdown.convert(html)).isEqualTo("Fish & chips — **crisp** ! ‌&unknown;")
    }

    @Test
    fun `persian text keeps its letters and its order`() {
        val html = "<p dir=\"rtl\">سلام <strong>دنیا</strong></p>"

        assertThat(HtmlMarkdown.convert(html)).isEqualTo("سلام **دنیا**")
    }

    @Test
    fun `unclosed paragraphs and list items are closed by the next one`() {
        val html = "<p>one<p>two<ul><li>a<li>b</ul>"

        assertThat(HtmlMarkdown.convert(html)).isEqualTo("one\n\ntwo\n\n- a\n- b")
    }

    @Test
    fun `a task list keeps its boxes`() {
        val html = """<ul><li><input type="checkbox" checked> done</li><li><input type="checkbox"> open</li></ul>"""

        assertThat(HtmlMarkdown.convert(html)).isEqualTo("- [x] done\n- [ ] open")
    }

    @Test
    fun `scripts, comments and picture data are left out`() {
        val html = "<p>a<script>if (1 < 2) alert('x')</script><!-- note -->b<img src=\"data:image/png;base64,AA\"></p>"

        assertThat(HtmlMarkdown.convert(html)).isEqualTo("ab")
    }

    @Test
    fun `a link whose text is its address is an autolink, and a script link is just its words`() {
        val html = """<p><a href="https://a.example">https://a.example</a> <a href="javascript:void(0)">no</a></p>"""

        assertThat(HtmlMarkdown.convert(html)).isEqualTo("<https://a.example> no")
    }

    @Test
    fun `a table cell's pipe is escaped and short rows are filled`() {
        val html = "<table><tr><td>a|b</td><td>c</td></tr><tr><td>d</td></tr></table>"

        assertThat(HtmlMarkdown.convert(html)).isEqualTo("| a\\|b | c |\n| --- | --- |\n| d |  |")
    }

    @Test
    fun `garbage never throws`() {
        val random = Random(SEED)
        val pieces =
            listOf(
                "<",
                ">",
                "</",
                "<p",
                "<p>",
                "</p>",
                "<b>",
                "</b>",
                "<ul>",
                "<li>",
                "</ul>",
                "<table>",
                "<tr>",
                "<td>",
                "<pre>",
                "</pre>",
                "<!--",
                "-->",
                "<!x",
                "&",
                "&amp",
                "&#",
                "&#x;",
                "&#99999999;",
                "\"",
                "'",
                "=",
                " ",
                "\n",
                "\r\n",
                "a",
                "سلام",
                "‏",
                "<a href=",
                "<img src=x",
                "<br/>",
                "<script>",
                "</script>",
                "<blockquote>",
                "<h1>",
                "<h7>",
                "<ol start=x>",
                "<input type=checkbox>",
            )
        repeat(ROUNDS) {
            val html = buildString { repeat(random.nextInt(1, 60)) { append(pieces.random(random)) } }
            HtmlMarkdown.convert(html)
        }
    }

    @Test
    fun `very deep nesting is read without running out of stack`() {
        val html = "<div>".repeat(DEEP) + "<b>deep</b>" + "</div>".repeat(DEEP)

        // Past the depth the tree stops at, the words are kept and the marks may not be.
        assertThat(HtmlMarkdown.convert(html)).contains("deep")
        assertThat(HtmlMarkdown.convert("<div>".repeat(SHALLOW) + "<b>deep</b>")).isEqualTo("**deep**")
        assertThat(HtmlMarkdown.convert("<ul><li>".repeat(DEEP) + "x")).isNotNull()
    }

    private companion object {
        const val SEED = 50
        const val ROUNDS = 2_000
        const val DEEP = 5_000
        const val SHALLOW = 100
    }
}
