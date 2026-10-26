package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * The vault's own settings and names, read from whatever a vault holds.
 *
 * A daily-notes format is typed by a person into Obsidian, a week note is
 * written by hand as often as by the phone, and a link from another app can
 * be anything at all. Random but seeded, so a failure repeats.
 */
class RobustnessTest {
    private val formats =
        listOf(
            "YYYY-MM-DD",
            "YYYY/MM/YYYY-MM-DD",
            "DD.MM.YY",
            "dddd, MMMM Do YYYY",
            "gggg-[W]ww",
            "GGGG-[W]WW",
            "YYYY-MM",
            "MMMM YYYY",
            "YYYY-[Q]Q",
            "YYYY",
            "[Daily] YYYY-MM-DD",
            "YYYY-MM-DD [at] dddd",
            "Qo [quarter] YYYY",
            "YYYY-DDDD",
            "Do MMM YY",
            "YY-M-D",
        )

    private val dates =
        Random(SEED).let { random ->
            List(ROUNDS) { LocalDate.of(2000, 1, 1).plusDays(random.nextLong(0, 365L * 40)) } +
                listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1))
        }

    @Test
    fun `a periodic note's name reads back as the period it was written for`() {
        formats.forEach { format ->
            val notes = PeriodicNotes(DailyNotes(folder = "Journal/", format = format))
            dates.forEach { date ->
                val start = notes.startOf(date)
                val path = notes.pathOf(start)
                assertThat(notes.periodOf(path)).isEqualTo(start)
                assertThat(notes.next(path)?.let(notes::periodOf)).isEqualTo(notes.shift(start, 1))
                assertThat(notes.previous(path)?.let(notes::periodOf)).isEqualTo(notes.shift(start, -1))
            }
        }
    }

    @Test
    fun `any format is formatted, and any path is read, without a throw`() {
        val random = Random(SEED)
        val letters =
            "YMDdwWQgGEeHhmsAa[]\\Do. -/:".toList().map { it.toString() } + listOf("[", "]", "[x", "\\", "سال")
        val patterns =
            formats + List(ROUNDS) { List(random.nextInt(0, 12)) { letters.random(random) }.joinToString("") }
        val paths =
            listOf(
                "",
                ".md",
                "Journal/.md",
                "Journal/2026-02-30.md",
                "Journal/9999999999-01-01.md",
                "x/y/z.md",
                "۲۰۲۶-۰۱-۰۱.md",
            )
        patterns.forEach { pattern ->
            MomentFormat.format(pattern, dates.random(random))
            MomentFormat.format(pattern, LocalDateTime.of(2026, 10, 26, 0, 5))
            val notes = PeriodicNotes(DailyNotes(folder = "Journal", format = pattern))
            paths.forEach { path ->
                notes.periodOf(path)
                notes.previous(path)
                notes.next(path)
            }
            notes.nearest(paths, "Journal/2026-01-01.md", 1)
            notes.yearsAgo(paths, "Journal/2026-01-01.md")
            Templates(
                dateFormat = pattern,
            ).fill("{{date}} {{time:$pattern}} {{title}} {{date:", "T", LocalDateTime.now())
        }
    }

    @Test
    fun `the plugins' settings files are read, or refused as not JSON`() {
        val random = Random(SEED)
        val json =
            listOf(
                "",
                "{",
                "[]",
                "null",
                "1",
                "\"s\"",
                "{\"items\": 3}",
                "{\"items\": [{\"type\": \"group\"}]}",
                "{\"items\": [{\"type\": \"group\", \"items\": [{\"type\": \"group\", \"items\": [{}]}]}]}",
                "{\"folder\": 5, \"format\": null, \"template\": []}",
                "{\"types\": {\"a\": 1, \"b\": {}}}",
            )
        val inputs = json + List(ROUNDS) { json.random(random).dropLast(random.nextInt(0, 3)) }
        inputs.forEach { text ->
            listOf(Bookmarks::parse, DailyNotes::parse, Templates::parse, PropertyTypes::parse).forEach { parse ->
                try {
                    parse(text)
                } catch (_: SerializationException) {
                    // Documented, and caught where the file is read.
                }
            }
        }
    }

    @Test
    fun `week notes and links from other apps read anything`() {
        val random = Random(SEED)
        val lines =
            listOf(
                "## Monday",
                "## Monday 32 October",
                "## Sunday, 1 Febtober",
                "- 09:00–10:00 Meeting",
                "- 25:99-26:00 x",
                "- 10:00 –",
                "    - with A",
                "- all day",
                "",
                "#",
                "\r",
            )
        val links =
            listOf(
                "obsidian://",
                "obsidian://open",
                "obsidian://open?vault=",
                "obsidian://open?vault=%ZZ&file=a",
                "obsidian://search?query=",
                "obsidian://open?file=%E2%82",
                "obsidian:///",
                "http://x",
                "",
            )
        repeat(ROUNDS) {
            val text = List(random.nextInt(0, 20)) { lines.random(random) }.joinToString("\n")
            WeekNotes.parse(
                text,
                listOf("Calendar/2026-W43.md", "Calendar/2026-W99.md", "x.md").random(random),
                ZoneOffset.UTC,
            )
            ObsidianUri.parse(links.random(random) + List(random.nextInt(0, 4)) { "&a=%" }.joinToString(""))
        }
    }

    private companion object {
        const val SEED = 50
        const val ROUNDS = 500
    }
}
