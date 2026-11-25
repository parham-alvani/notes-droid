package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.icons.IconicConfig
import me.parham1995.notes.markdown.StatusType
import org.junit.Test
import java.time.LocalDate

/**
 * Daftar's own settings in place of `.obsidian/`: the new place first, the
 * old file when it is not there, never the two together. The first six are
 * the tests every Daftar app must have (`no-obsidian-folder.md` in
 * notes-linux's `docs/specs/`), by the spec's names; the sixth is
 * `theFolderIsSkipped` in `VaultFilterTest`, where the phone decides it.
 */
class DaftarPlacesTest {
    private fun places(toml: String) = DaftarPlaces.of(toml)

    @Test
    fun theNewPlaceWins() {
        val app = """{"attachmentFolderPath": "uploads"}"""
        assertThat(places("[files]\nattachments = \"media\"\n").attachments(app)).isEqualTo("media")
        assertThat(places("").attachments(app)).isEqualTo("uploads")
        assertThat(places("").attachments(null)).isEqualTo("")
        // The excluded files go the same way, whole.
        val ignored = """{"userIgnoreFilters": ["uploads/"]}"""
        assertThat(places("[files]\nignore = [\"media/\"]\n").ignoreFilters(ignored)).containsExactly("media/")
        assertThat(places("").ignoreFilters(ignored)).containsExactly("uploads/")
    }

    @Test
    fun statusesFromTheSettings() {
        val toml =
            """
            [tasks.statuses.in_review]
            symbol = "r"
            name = "In Review"
            next = "x"
            type = "IN_PROGRESS"
            """.trimIndent()
        // The plugin's file says otherwise, and is not read.
        val plugin =
            """{"statusSettings": {"customStatuses": [{"symbol": "q", "name": "Question", "type": "TODO"}]}}"""
        val statuses = places(toml).statuses(plugin)
        assertThat(statuses.all.map { it.symbol }).containsExactly(' ', 'x', '/', '-', 'r')
        val review = statuses.of('r')
        assertThat(review.name).isEqualTo("In Review")
        assertThat(review.next).isEqualTo('x')
        assertThat(review.type).isEqualTo(StatusType.IN_PROGRESS)
        // With no section, the plugin's file as before.
        assertThat(places("").statuses(plugin).all.map { it.symbol }).contains('q')
    }

    @Test
    fun `a section with one of the four symbols renames it`() {
        val statuses = places("[tasks.statuses.done]\nsymbol = \"x\"\nname = \"Finished\"\n").statuses(null)
        assertThat(statuses.of('x').name).isEqualTo("Finished")
        assertThat(statuses.of('x').type).isEqualTo(StatusType.DONE)
        assertThat(statuses.all).hasSize(4)
    }

    @Test
    fun dailyNotesFromTheSettings() {
        val daily = places("[daily]\nformat = \"YYYY-[W]ww\"\n").dailyNotes("""{"format": "YYYY-MM-DD"}""")
        assertThat(daily.pathFor(LocalDate.of(2026, 11, 25))).isEqualTo("2026-W48.md")
        assertThat(places("").dailyNotes("""{"folder": "Daily"}""").pathFor(LocalDate.of(2026, 11, 25)))
            .isEqualTo("Daily/2026-11-25.md")
    }

    @Test
    fun iconsFromTheFolder() {
        val iconic = """{"fileIcons": {"Tasks/Work.md": {"icon": "lucide-briefcase"}}}"""
        val other = """{"fileIcons": {"Tasks/Work.md": {"icon": "lucide-star"}}}"""

        fun icon(text: String?) = text?.let { IconicConfig.parse(it).forPath("Tasks/Work.md", isFolder = false) }
        // The same JSON gives the same icons from either place.
        assertThat(icon(DaftarPlaces.firstOf(iconic, null))).isNotNull()
        assertThat(icon(DaftarPlaces.firstOf(iconic, null))).isEqualTo(icon(DaftarPlaces.firstOf(null, iconic)))
        // With both, `.daftar/` wins.
        assertThat(icon(DaftarPlaces.firstOf(iconic, other))).isEqualTo(icon(iconic))
        assertThat(icon(DaftarPlaces.firstOf(iconic, other))).isNotEqualTo(icon(other))
    }

    @Test
    fun homeFromTheSettings() {
        val toml = "[home]\ngreeting = \"Hello, Parham.\"\nclock = \"12-hour\"\nshow_quote = true\n"
        val quotes = """[{"text": "Less, but better.", "author": "Dieter Rams"}]"""
        val home = places(toml).home("""{"greetingText": "Hi", "timeFormat": "24-hour"}""", quotes)
        assertThat(home.greetingText).isEqualTo("Hello, Parham.")
        assertThat(home.twentyFourHour).isFalse()
        assertThat(home.quotes).containsExactly(Beautitab.Quote("Less, but better.", "Dieter Rams"))
        assertThat(home.showQuote).isTrue()
        // No `home.*`: the plugin's file, with Daftar's quotes in place of its own.
        val plugin = places("").home("""{"greetingText": "Hi", "showQuote": true}""", quotes)
        assertThat(plugin.greetingText).isEqualTo("Hi")
        assertThat(plugin.showQuote).isTrue()
        assertThat(plugin.quotes.single().author).isEqualTo("Dieter Rams")
    }

    @Test
    fun `property types come from the settings, else types json`() {
        val types =
            places(
                "[properties.types]\njob_next = \"datetime\"\n",
            ).propertyTypes("""{"types": {"a": "text"}}""")
        assertThat(types).containsExactly("job_next", "datetime")
        assertThat(places("").propertyTypes("""{"types": {"a": "text"}}""")).containsExactly("a", "text")
    }

    @Test
    fun `the templates folder comes from the settings`() {
        assertThat(places("[templates]\nfolder = \"Templates/\"\n").templates("""{"folder": "Old"}""").folder)
            .isEqualTo("Templates")
        assertThat(places("").templates("""{"folder": "Old"}""").folder).isEqualTo("Old")
    }
}
