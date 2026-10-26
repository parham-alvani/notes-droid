package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Beautitab plugin's settings, read the way the Mac reads them. Synthetic values. */
class BeautitabTest {
    @Test
    fun `a full file is read`() {
        val tab =
            Beautitab.parse(
                """
                {
                  "showTime": true,
                  "timeFormat": "24-hour",
                  "showGreeting": true,
                  "greetingText": "  Hello, Ada.  ",
                  "showInlineSearch": false,
                  "showRecentFiles": true,
                  "showBookmarks": true,
                  "showQuote": true,
                  "quoteSource": "My quotes",
                  "customQuotes": [
                    {"text": "Read slowly.", "author": "Someone"},
                    {"text": "  ", "author": "Nobody"},
                    {"text": "No author"}
                  ],
                  "customBackground": "Pictures/hill.webp",
                  "localBackgrounds": ["Pictures/sea.jpg", {"path": "Pictures/field.png"}, ""],
                  "somethingNew": {"nested": [1, 2]}
                }
                """.trimIndent(),
            )
        assertThat(tab.twentyFourHour).isTrue()
        assertThat(tab.greetingText).isEqualTo("Hello, Ada.")
        assertThat(tab.showSearch).isFalse()
        assertThat(tab.showBookmarks).isTrue()
        assertThat(tab.showQuote).isTrue()
        assertThat(tab.quotes)
            .containsExactly(Beautitab.Quote("Read slowly.", "Someone"), Beautitab.Quote("No author"))
            .inOrder()
        assertThat(tab.backgrounds)
            .containsExactly("Pictures/hill.webp", "Pictures/sea.jpg", "Pictures/field.png")
            .inOrder()
        assertThat(tab.vaultBackground).isEqualTo("Pictures/hill.webp")
    }

    @Test
    fun `an empty object is the Mac's defaults`() {
        assertThat(Beautitab.parse("{}")).isEqualTo(Beautitab())
        val tab = Beautitab()
        assertThat(tab.showTime).isTrue()
        assertThat(tab.twentyFourHour).isNull()
        assertThat(tab.showGreeting).isTrue()
        assertThat(tab.showSearch).isTrue()
        assertThat(tab.showRecentFiles).isTrue()
        assertThat(tab.showBookmarks).isFalse()
        assertThat(tab.showQuote).isFalse()
    }

    @Test
    fun `a value of the wrong type is the default`() {
        val tab =
            Beautitab.parse(
                """
                {"showTime": "false", "showGreeting": 0, "showBookmarks": null, "timeFormat": 24,
                 "greetingText": ["x"], "customQuotes": {"text": "x"}, "customBackground": 7,
                 "localBackgrounds": "Pictures/a.png"}
                """.trimIndent(),
            )
        assertThat(tab).isEqualTo(Beautitab())
    }

    @Test
    fun `the time format is the phone's own unless it says which`() {
        assertThat(Beautitab.parse("""{"timeFormat": "12-hour"}""").twentyFourHour).isFalse()
        assertThat(Beautitab.parse("""{"timeFormat": "24-HOUR"}""").twentyFourHour).isTrue()
        assertThat(Beautitab.parse("""{"timeFormat": "sundial"}""").twentyFourHour).isNull()
    }

    @Test
    fun `quotes show only from the person's own and only when there is one`() {
        val quotes = """"customQuotes": [{"text": "x"}]"""
        assertThat(Beautitab.parse("""{"showQuote": true, $quotes}""").showQuote).isTrue()
        assertThat(Beautitab.parse("""{"showQuote": true, "quoteSource": "My quotes", $quotes}""").showQuote).isTrue()
        assertThat(Beautitab.parse("""{"showQuote": true, "quoteSource": "Quotable", $quotes}""").showQuote).isFalse()
        assertThat(Beautitab.parse("""{"showQuote": true, "customQuotes": []}""").showQuote).isFalse()
        assertThat(Beautitab.parse("""{"showQuote": false, $quotes}""").showQuote).isFalse()
    }

    @Test
    fun `only a picture in the vault is a background the phone can show`() {
        assertThat(Beautitab.vaultPicture("Pictures/hill.WEBP")).isEqualTo("Pictures/hill.WEBP")
        assertThat(Beautitab.vaultPicture(" Pictures/hill.jpg ")).isEqualTo("Pictures/hill.jpg")
        assertThat(Beautitab.vaultPicture("https://example.com/a.jpg")).isNull()
        assertThat(Beautitab.vaultPicture("data:image/png;base64,AAAA")).isNull()
        assertThat(Beautitab.vaultPicture("/Users/someone/a.jpg")).isNull()
        assertThat(Beautitab.vaultPicture("~/a.jpg")).isNull()
        assertThat(Beautitab.vaultPicture("../outside.png")).isNull()
        assertThat(Beautitab.vaultPicture("Notes/a.md")).isNull()
        assertThat(Beautitab.vaultPicture("")).isNull()
        val tab = Beautitab(backgrounds = listOf("https://example.com/a.jpg", "Pictures/b.png"))
        assertThat(tab.vaultBackground).isEqualTo("Pictures/b.png")
    }

    @Test
    fun `the greeting fills in the part of the day or falls back`() {
        assertThat(Home.greeting("Good {{greeting}}, Ada.", "evening", "Good evening")).isEqualTo("Good evening, Ada.")
        assertThat(Home.greeting("Hello, Ada.", "evening", "Good evening")).isEqualTo("Hello, Ada.")
        assertThat(Home.greeting("", "morning", "Good morning")).isEqualTo("Good morning")
        assertThat(PartOfDay.of(4)).isEqualTo(PartOfDay.EVENING)
        assertThat(PartOfDay.of(5)).isEqualTo(PartOfDay.MORNING)
        assertThat(PartOfDay.of(12)).isEqualTo(PartOfDay.AFTERNOON)
        assertThat(PartOfDay.of(18)).isEqualTo(PartOfDay.EVENING)
    }
}
