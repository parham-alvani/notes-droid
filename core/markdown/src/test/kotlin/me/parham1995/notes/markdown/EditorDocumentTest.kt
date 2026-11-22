package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** That a key press reads its own line again and no other. */
class EditorDocumentTest {
    private fun EditorDocument.shape(): List<String> =
        List(size) {
            val line = line(it)
            "${start(
                it,
            )} ${line.length} ${line.kind} ${line.level} ${line.quote} ${line.callout} ${line.spans} ${line.marks}"
        }

    private fun fresh(text: String) = EditorDocument().apply { update(text) }

    @Test
    fun `the lines know where they start`() {
        val document = fresh("# A\n\nbody")
        assertThat(document.size).isEqualTo(3)
        assertThat(List(3) { document.start(it) }).containsExactly(0, 4, 5).inOrder()
        assertThat(document.lineAt(0)).isEqualTo(0)
        assertThat(document.lineAt(3)).isEqualTo(0)
        assertThat(document.lineAt(4)).isEqualTo(1)
        assertThat(document.lineAt(9)).isEqualTo(2)
    }

    @Test
    fun `a key reads one line of a long note`() {
        val note = (1..500).joinToString("\n") { "line $it with **bold**" }
        val document = fresh(note)
        assertThat(document.lastRead).isEqualTo(500)
        val kept = document.line(10)
        val changed = document.line(250)

        val at = document.start(250) + 3
        document.update(note.substring(0, at) + "x" + note.substring(at))

        assertThat(document.lastRead).isEqualTo(1)
        assertThat(document.line(10)).isSameInstanceAs(kept)
        assertThat(document.line(400).length).isEqualTo("line 401 with **bold**".length)
        assertThat(document.line(250)).isNotSameInstanceAs(changed)
        assertThat(document.start(251)).isEqualTo(document.start(250) + document.line(250).length + 1)
    }

    @Test
    fun `a heading is found by its number, and one in a code block is not counted`() {
        val document = fresh("# A\n\n```\n# not one\n```\n\n## B\ntext")
        assertThat(document.headingEnd(0)).isEqualTo(3)
        assertThat(document.headingEnd(1)).isEqualTo(document.text.indexOf("## B") + 4)
        assertThat(document.headingEnd(2)).isNull()
    }

    @Test
    fun `a place is under the heading before it, by the same number`() {
        val document = fresh("intro\n# A\n\n```\n# not one\n```\n\n## B\ntext")
        assertThat(document.headingAt(0)).isNull()
        assertThat(document.headingAt(document.text.indexOf("# A"))).isEqualTo(0)
        assertThat(document.headingAt(document.text.indexOf("# not one") + 2)).isEqualTo(0)
        assertThat(document.headingAt(document.text.length)).isEqualTo(1)
        // The number is the one `headingEnd` opens at.
        assertThat(document.headingAt(document.headingEnd(1)!!)).isEqualTo(1)
        assertThat(EditorDocument().headingAt(0)).isNull()
    }

    @Test
    fun `the same text reads nothing`() {
        val document = fresh("a\nb")
        document.update("a\nb")
        assertThat(document.lastRead).isEqualTo(0)
    }

    @Test
    fun `a fence typed above makes the lines under it code, until the next fence`() {
        val document = fresh("a\n\n**b**\n```\nc\n```\n**d**")
        document.update("a\n```\n**b**\n```\nc\n```\n**d**")
        assertThat(List(document.size) { document.line(it).kind })
            .containsExactly(
                EditorLineKind.TEXT,
                EditorLineKind.FENCE,
                EditorLineKind.CODE,
                EditorLineKind.FENCE,
                EditorLineKind.TEXT,
                EditorLineKind.FENCE,
                EditorLineKind.CODE,
            ).inOrder()
        assertThat(document.shape()).isEqualTo(fresh(document.text).shape())
    }

    @Test
    fun `a new line and a joined line keep the rest`() {
        val document = fresh("one\ntwo\nthree")
        val three = document.line(2)
        document.update("one\ntw\no\nthree")
        assertThat(document.size).isEqualTo(4)
        assertThat(document.line(3)).isSameInstanceAs(three)
        document.update("onetw\no\nthree")
        assertThat(document.size).isEqualTo(3)
        assertThat(document.line(2)).isSameInstanceAs(three)
        assertThat(document.shape()).isEqualTo(fresh(document.text).shape())
    }

    @Test
    fun `any run of changes reads as the whole note does`() {
        val pieces =
            listOf(
                "a",
                " ",
                "\n",
                "**",
                "`",
                "```\n",
                "> ",
                "> [!tip] t\n",
                "---\n",
                "# ",
                "==",
                "[[x]]",
                "$$\n",
                "%%",
                "ب",
                "- ",
            )
        val random = Random(7)
        val document = EditorDocument()
        var text = ""
        repeat(2000) {
            val at = if (text.isEmpty()) 0 else random.nextInt(text.length + 1)
            text =
                if (text.isNotEmpty() && random.nextInt(3) == 0) {
                    val end = minOf(text.length, at + random.nextInt(4))
                    text.removeRange(at, end)
                } else {
                    text.substring(0, at) + pieces[random.nextInt(pieces.size)] + text.substring(at)
                }
            document.update(text)
            assertThat(document.shape()).isEqualTo(fresh(text).shape())
        }
    }
}
