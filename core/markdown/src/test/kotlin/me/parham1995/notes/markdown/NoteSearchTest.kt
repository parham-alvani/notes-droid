package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NoteSearchTest {
    private fun find(
        markdown: String,
        query: String,
    ) = NoteSearch.find(MarkdownParser.parseNote(markdown).blocks, query)

    @Test
    fun `finds a word and says which block it is in`() {
        val found = find("# Title\n\nSome prose about limits.\n", "limits")

        assertThat(found).hasSize(1)
        assertThat(found.single().blockIndex).isGreaterThan(0)
        assertThat(found.single().preview).contains("limits")
    }

    @Test
    fun `every occurrence is reported, not just the first in a block`() {
        val found = find("rate here and rate again and rate once more\n", "rate")

        assertThat(found).hasSize(3)
    }

    @Test
    fun `matching ignores case, because nobody types the case they are looking for`() {
        assertThat(find("The Gateway is down\n", "gateway")).hasSize(1)
        assertThat(find("the gateway is down\n", "GATEWAY")).hasSize(1)
    }

    @Test
    fun `a phrase is found across formatting inside it`() {
        // Matching the rendered text rather than the markdown: a bolded word in
        // the middle of a phrase should not hide the phrase.
        val found = find("the **rate** limiter is fine\n", "rate limiter")

        assertThat(found).hasSize(1)
    }

    @Test
    fun `code is searched, which is where hostnames and flags live`() {
        val found = find("```bash\ncurl https://admin.example.ir --insecure\n```\n", "insecure")

        assertThat(found).hasSize(1)
    }

    @Test
    fun `tables, callouts and lists are all searched`() {
        assertThat(find("| a | b |\n| --- | --- |\n| needle | c |\n", "needle")).hasSize(1)
        assertThat(find("> [!note] Title\n>\n> a needle here\n", "needle")).hasSize(1)
        assertThat(find("- one\n- a needle\n", "needle")).hasSize(1)
    }

    @Test
    fun `the preview marks where the term is`() {
        val found = find("a".repeat(100) + " needle " + "b".repeat(100) + "\n", "needle").single()

        assertThat(found.preview.substring(found.previewStart, found.previewEnd)).isEqualTo("needle")
        // Trimmed around the hit rather than handing back the whole paragraph.
        assertThat(found.preview.length).isLessThan(100)
    }

    @Test
    fun `an empty or blank query finds nothing rather than everything`() {
        assertThat(find("some text\n", "")).isEmpty()
        assertThat(find("some text\n", "   ")).isEmpty()
    }

    @Test
    fun `a term that is not there returns nothing`() {
        assertThat(find("some text\n", "absent")).isEmpty()
    }

    @Test
    fun `persian is found the same as anything else`() {
        val found = find("این یک یادداشت فارسی است\n", "یادداشت")

        assertThat(found).hasSize(1)
    }

    @Test
    fun `every preview range lies inside its preview`() {
        // These become spans on screen, and a range outside the string is a
        // crash rather than a missing highlight.
        val found = find("needle at the very start of the block\n", "needle").single()

        assertThat(found.previewStart).isAtLeast(0)
        assertThat(found.previewEnd).isAtMost(found.preview.length)
        assertThat(found.previewStart).isAtMost(found.previewEnd)
    }
}
