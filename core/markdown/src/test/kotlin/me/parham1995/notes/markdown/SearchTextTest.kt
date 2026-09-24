package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Arabic and Persian letter forms, made one for search.
 *
 * The letters are spelled as escapes: ي and ی are indistinguishable on screen,
 * which is the whole problem, and a test that relied on reading them would be
 * no test at all.
 */
class SearchTextTest {
    private val yehArabic = 'ي'
    private val yehPersian = 'ی'
    private val kafArabic = 'ك'
    private val kafPersian = 'ک'
    private val zwnj = '‌'

    @Test
    fun `arabic yeh and kaf become the persian letters`() {
        assertThat(SearchText.normalize("${kafArabic}ار")).isEqualTo("${kafPersian}ار")
        assertThat(SearchText.normalize("${yehArabic}ك")).isEqualTo("$yehPersian$kafPersian")
        // Alef maksura is a yeh too, in Persian.
        assertThat(SearchText.normalize("موسى")).isEqualTo("موس$yehPersian")
    }

    @Test
    fun `persian text is already normal`() {
        val text = "${kafPersian}تاب$yehPersian خوب"
        assertThat(SearchText.normalize(text)).isSameInstanceAs(text)
    }

    @Test
    fun `english text is returned untouched`() {
        val text = "Kubernetes networking, 100% done [x] -- naive cafe"
        assertThat(SearchText.normalize(text)).isSameInstanceAs(text)
    }

    @Test
    fun `teh marbuta and heh with yeh above become heh`() {
        assertThat(SearchText.normalize("علاقة")).isEqualTo("علاقه")
        assertThat(SearchText.normalize("خانۀ")).isEqualTo("خانه")
    }

    @Test
    fun `persian and arabic-indic digits become ascii`() {
        assertThat(SearchText.normalize("۱۴۰۴")).isEqualTo("1404")
        assertThat(SearchText.normalize("١٤٠٤")).isEqualTo("1404")
        assertThat(SearchText.normalize("۰۱۲۳۴۵۶۷۸۹"))
            .isEqualTo("0123456789")
        assertThat(SearchText.normalize("٠١٢٣٤٥٦٧٨٩"))
            .isEqualTo("0123456789")
        assertThat(SearchText.normalize("1404")).isEqualTo("1404")
    }

    @Test
    fun `zwnj is nothing`() {
        assertThat(SearchText.normalize("می${zwnj}خواهم"))
            .isEqualTo("میخواهم")
    }

    @Test
    fun `tatweel and every vowel mark are removed`() {
        assertThat(SearchText.normalize("کــتاب")).isEqualTo("کتاب")
        // Fathatan through the last of the range, and the superscript alef.
        for (mark in 'ً'..'ٟ') {
            assertThat(SearchText.normalize("ب${mark}ا")).isEqualTo("با")
        }
        assertThat(SearchText.normalize("هٰذا")).isEqualTo("هذا")
        // Heh with a hamza above, written as two characters, ends up as heh.
        assertThat(SearchText.normalize("خانهٔ")).isEqualTo("خانه")
    }

    @Test
    fun `alef variants are left alone`() {
        val text = "آب أحمد إیران"
        assertThat(SearchText.normalize(text)).isSameInstanceAs(text)
    }

    @Test
    fun `mixed english and persian keeps the english`() {
        val text = "deploy ${kafArabic}ار on prod-۱۲ at 10:30, see [[Runbook]]"
        assertThat(SearchText.normalize(text)).isEqualTo("deploy ${kafPersian}ار on prod-12 at 10:30, see [[Runbook]]")
    }

    @Test
    fun `the snippet markers can never be text`() {
        assertThat(SearchText.normalize("abcd")).isEqualTo("abcd")
    }

    @Test
    fun `normalizing twice changes nothing more`() {
        val text = "كِتاب می${zwnj}خواهم ۱۴۰۴"
        val once = SearchText.normalize(text)
        assertThat(SearchText.normalize(once)).isSameInstanceAs(once)
    }

    @Test
    fun `a name folds case and letter forms`() {
        assertThat(SearchText.foldName("${kafArabic}ار Notes")).isEqualTo("${kafPersian}ار notes")
    }

    @Test
    fun `mapped text agrees with normalize and points back into the original`() {
        val text = "aكِb${zwnj}c"
        val mapped = SearchText.mapped(text)
        assertThat(mapped.text).isEqualTo(SearchText.normalize(text))
        assertThat(mapped.text).isEqualTo("a${kafPersian}bc")
        assertThat((0..mapped.text.length).map(mapped::originOf)).containsExactly(0, 1, 3, 5, 6).inOrder()
    }

    @Test
    fun `a snippet of unchanged text only has its markers translated`() {
        val snippet = "the red harvest"
        assertThat(SearchText.restoreSnippet(snippet, null)).isEqualTo("...the [red] harvest...")
    }

    @Test
    fun `a snippet shows the characters the note was written in`() {
        val original = "این كار را می${zwnj}خواهم"
        val normalized = SearchText.normalize(original)
        // What FTS5 returns: normalized text, the match marked.
        val snippet = normalized.replace("کار", "کار")

        val shown = SearchText.restoreSnippet(snippet, original)

        assertThat(shown).isEqualTo("این [كار] را می${zwnj}خواهم")
    }

    @Test
    fun `a removed character inside a match stays inside the highlight`() {
        val original = "من می${zwnj}خواهمُ بروم"
        val snippet = "من میخواهم بروم"

        assertThat(SearchText.restoreSnippet(snippet, original))
            .isEqualTo("من [می${zwnj}خواهمُ] بروم")
    }

    @Test
    fun `a cut excerpt keeps its ellipses and only its own span`() {
        val original = "first line. كار in the middle. last line"
        val snippet = "کار in the"

        assertThat(SearchText.restoreSnippet(snippet, original)).isEqualTo("...[كار] in the...")
    }

    @Test
    fun `digits come back as they were written`() {
        val original = "report ۱۴۰۴ final"
        val snippet = "report 1404 final"

        assertThat(SearchText.restoreSnippet(snippet, original)).isEqualTo("report [۱۴۰۴] final")
    }

    @Test
    fun `an excerpt that cannot be found again is still shown`() {
        val snippet = "abc def"
        assertThat(SearchText.restoreSnippet(snippet, "something else entirely")).isEqualTo("[abc] def")
    }
}
