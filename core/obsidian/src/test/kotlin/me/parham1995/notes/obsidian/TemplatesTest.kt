package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/** Every fixture here is made up. The repository is public; the vault is not. */
class TemplatesTest {
    private val at = LocalDateTime.of(2026, 10, 26, 9, 5, 7)

    @Test
    fun `the plugin's settings are read, defaults filling the gaps`() {
        assertThat(Templates.parse("""{"folder": "/Templates/"}""")).isEqualTo(Templates(folder = "Templates"))
        assertThat(Templates.parse("""{"folder": "T", "dateFormat": "DD.MM.YYYY", "timeFormat": " "}"""))
            .isEqualTo(Templates("T", "DD.MM.YYYY", Templates.DEFAULT_TIME))
        assertThat(Templates.parse("{}")).isEqualTo(Templates())
    }

    @Test
    fun `variables are filled the way Obsidian fills them`() {
        val templates = Templates(folder = "Templates", dateFormat = "dddd D MMMM", timeFormat = "h:mm A")
        val text = "# {{title}}\n\nOn {{date}} at {{time}}.\n{{date:YYYY-MM-DD HH:mm:ss}} {{ TIME:HH[h]mm }} {{other}}"
        assertThat(templates.fill(text, "Weekly review", at))
            .isEqualTo("# Weekly review\n\nOn Monday 26 October at 9:05 AM.\n2026-10-26 09:05:07 09h05 {{other}}")
    }

    @Test
    fun `a date format without a time reads as it did`() {
        // `mm` alone in a date format means minutes to moment, and the daily
        // note path never had a time to give it.
        assertThat(MomentFormat.format("YYYY-MM-DD", at.toLocalDate())).isEqualTo("2026-10-26")
        assertThat(MomentFormat.format("YYYY-MM-DD", at)).isEqualTo("2026-10-26")
    }

    @Test
    fun `only notes in the folder are templates`() {
        val templates = Templates(folder = "Templates")
        assertThat(templates.holds("Templates/Meeting.md")).isTrue()
        assertThat(templates.holds("Templates/Sub/Meeting.md")).isTrue()
        assertThat(templates.holds("TemplatesX/Meeting.md")).isFalse()
        assertThat(Templates().holds("Meeting.md")).isFalse()
    }

    @Test
    fun `a title becomes a file name`() {
        assertThat(Templates.fileName("  Q3: plans / ideas? ")).isEqualTo("Q3 plans ideas")
        assertThat(Templates.fileName("...")).isEmpty()
    }
}
