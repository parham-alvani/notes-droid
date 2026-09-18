package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CodeGrammarsTest {
    private fun kinds(
        code: String,
        language: String,
    ) = CodeGrammars.tokenize(code, language)!!.map { it.kind to code.substring(it.start, it.end) }

    @Test
    fun `yaml colours its keys, values and comments`() {
        val found = kinds("# a note\nname: daftar\nport: 8080\nenabled: true\n", "yaml")

        assertThat(found).containsAtLeast(
            TokenKind.COMMENT to "# a note",
            TokenKind.KEY to "name",
            TokenKind.KEY to "port",
            TokenKind.NUMBER to "8080",
            TokenKind.KEYWORD to "true",
        )
    }

    @Test
    fun `a word with a colon after it is only a key at the start of a line`() {
        // Otherwise every "see: this" inside a value would be coloured as a key.
        val found = kinds("note: see the thing: here\n", "yaml")

        assertThat(found.filter { it.first == TokenKind.KEY }.map { it.second }).containsExactly("note")
    }

    @Test
    fun `sql keywords are matched however they are written`() {
        val found = kinds("SELECT id FROM notes where id = 4 -- comment", "sql")

        assertThat(found).containsAtLeast(
            TokenKind.KEYWORD to "SELECT",
            TokenKind.KEYWORD to "FROM",
            TokenKind.KEYWORD to "where",
            TokenKind.NUMBER to "4",
            TokenKind.COMMENT to "-- comment",
        )
    }

    @Test
    fun `a sql block comment runs to its close`() {
        val found = kinds("/* several\n   lines */ SELECT 1", "sql")

        assertThat(found.first()).isEqualTo(TokenKind.COMMENT to "/* several\n   lines */")
        // And the scan carries on afterwards rather than stopping there.
        assertThat(found).contains(TokenKind.KEYWORD to "SELECT")
    }

    @Test
    fun `an unclosed block comment ends at the end rather than looping`() {
        val found = kinds("/* never closed\nSELECT 1", "sql")

        assertThat(found).hasSize(1)
        assertThat(found.single().first).isEqualTo(TokenKind.COMMENT)
    }

    @Test
    fun `strings keep their quotes and survive an escape`() {
        val found = kinds("""name: "a \" quote"\n""", "yaml")

        assertThat(found).contains(TokenKind.STRING to """"a \" quote"""")
    }

    @Test
    fun `an unterminated string stops at the line, not the file`() {
        // A fence in a note is often a fragment. Running to the end of the
        // input would paint the rest of the block as a string.
        val found = kinds("name: \"unclosed\nport: 80\n", "yaml")

        assertThat(found).contains(TokenKind.KEY to "port")
    }

    @Test
    fun `hcl reads both comment styles and its own keywords`() {
        val found = kinds("# one\n// two\nresource \"aws_s3_bucket\" \"b\" {\n  acl = \"private\"\n}", "hcl")

        assertThat(found).containsAtLeast(
            TokenKind.COMMENT to "# one",
            TokenKind.COMMENT to "// two",
            TokenKind.KEYWORD to "resource",
            TokenKind.KEY to "acl",
        )
    }

    @Test
    fun `json has no comments, so a hash is just a character`() {
        val found = kinds("""{"name": "a # b", "n": 1}""", "json")

        assertThat(found.none { it.first == TokenKind.COMMENT }).isTrue()
        assertThat(found).contains(TokenKind.NUMBER to "1")
    }

    @Test
    fun `promql and powershell and zig all resolve`() {
        assertThat(CodeGrammars.handles("promql")).isTrue()
        assertThat(CodeGrammars.handles("powershell")).isTrue()
        assertThat(CodeGrammars.handles("zig")).isTrue()
        assertThat(CodeGrammars.handles("yml")).isTrue()
        assertThat(CodeGrammars.handles("terraform")).isTrue()
    }

    @Test
    fun `a language nothing here knows returns null rather than guessing`() {
        assertThat(CodeGrammars.tokenize("whatever", "brainfuck")).isNull()
        assertThat(CodeGrammars.tokenize("whatever", null)).isNull()
        assertThat(CodeGrammars.handles("text")).isFalse()
    }

    @Test
    fun `every token lies inside the code and none overlap`() {
        val code =
            """
            # comment
            name: "value"
            count: 42
            nested:
              key: false
            """.trimIndent()
        val tokens = CodeGrammars.tokenize(code, "yaml")!!

        // The renderer applies these as spans, and a span outside the string or
        // overlapping another is an immediate crash rather than a wrong colour.
        assertThat(tokens.all { it.start in 0..code.length && it.end in it.start..code.length }).isTrue()
        tokens.zipWithNext().forEach { (a, b) ->
            assertThat(a.end).isAtMost(b.start)
        }
    }

    @Test
    fun `a number needs to start a word, not end one`() {
        // `id2` is an identifier; colouring the 2 inside it looks like a bug.
        val found = kinds("key: id2\n", "yaml")

        assertThat(found.none { it.first == TokenKind.NUMBER }).isTrue()
    }
}
