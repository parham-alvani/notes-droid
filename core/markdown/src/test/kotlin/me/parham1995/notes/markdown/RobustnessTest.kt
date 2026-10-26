package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The parsers against what a vault can hold and none of their tests wrote:
 * empty files, unterminated fences, huge lines, right-to-left text, CRLF,
 * lists nested past any sense, and plain noise.
 *
 * Every one of these runs over every note on every sync, so a throw on one
 * odd file is an index that never finishes, or a note that never opens. The
 * input is random but seeded, so a failure is the same failure every run.
 */
class RobustnessTest {
    /** Notes that have broken something somewhere, written down once. */
    private val awkward =
        listOf(
            "",
            "\n",
            "\r\n\r\n",
            "```",
            "```kotlin\nfun a() {\n",
            "~~~\n```\n",
            "---",
            "---\n",
            "---\ntitle: [unclosed\n",
            "---\na: b\n---",
            "> [!note",
            "> [!note]- Title\n> > [!tip]\n> > > deep",
            "[[",
            "]]",
            "[[a|",
            "![[",
            "![[#^",
            "[[#]]",
            "[[a#b#c#^d|e]]",
            "==",
            "==🔴",
            "$$",
            "\$a",
            "^[",
            "[^",
            "[^1]:",
            "%%",
            "%% unclosed comment",
            "<!--",
            "<div>",
            "- [ ]",
            "- [",
            "- [x] ✅",
            "- [ ] 📅 2026-02-30",
            "- [ ] 🔁 every",
            "| a |\n|",
            "| a | b |\n| --- | --- |\n| c |",
            "#",
            "#######",
            "# " + "x".repeat(200),
            "#tag/",
            "#/",
            "x".repeat(100_000),
            "سلام **دنیا** [[یادداشت]] #برچسب",
            "\u200f\u200e\u202e\u2066",
            "\u0000\u0001\uFFFF",
            "\uD83D",
            "😀".repeat(1_000),
            (0 until 200).joinToString("\n") { "    ".repeat(it) + "- item $it" },
            (0 until 200).joinToString("\n") { ">".repeat(it) + " quote" },
            "- a\r\n    - b\r\n        - [ ] c\r\n",
            "[text](<unclosed",
            "[text](a%ZZ.md)",
            "<https://",
            "https://example.com/" + "a".repeat(5_000),
        )

    private val pieces =
        awkward.filter { it.length < 300 } +
            listOf(" ", "\n", "\t", "a", "#", "-", "*", "[", "]", "(", ")", "|", "`", "^", "!")

    private fun garbage(
        random: Random,
        max: Int = 40,
    ) = buildString { repeat(random.nextInt(0, max)) { append(pieces.random(random)) } }

    private fun inputs(): List<String> {
        val random = Random(SEED)
        return awkward + List(ROUNDS) { garbage(random) }
    }

    @Test
    fun `the parser reads anything, and CRLF reads as LF does`() {
        inputs().forEach { text ->
            val note = MarkdownParser.parseNote(text)
            val crlf = MarkdownParser.parseNote(text.replace("\r\n", "\n").replace("\n", "\r\n"))
            assertThat(crlf.blocks.size).isEqualTo(MarkdownParser.parseNote(text.replace("\r\n", "\n")).blocks.size)
            // Everything downstream of the parser that walks the blocks.
            SpokenText.of(note.blocks)
            ReadingTime.of(note.blocks)
            note.blocks.footnotes()
            HeadingFolds.visible(note.blocks, note.blocks.indices.toSet())
            NoteSearch.find(note.blocks, "a")
        }
    }

    @Test
    fun `front matter and yaml never throw`() {
        val yaml =
            listOf(
                "a: [1, 2",
                "a: {b: c",
                "- - - x",
                "a:\n  - b\n - c\n   d: e",
                ": nothing",
                "'quoted: key': \"value",
                "a: |\n  block\n b",
                "a: >-\n",
                "\t\ta: tab",
                "a: &anchor *alias",
                "? complex\n: key",
            )
        val random = Random(SEED)
        (yaml + inputs() + List(ROUNDS) { yaml.random(random) + "\n" + garbage(random) }).forEach { text ->
            Yaml.parse(text)
            FrontMatter.parse("---\n$text\n---\nbody")
            FrontMatter.parse(text)
        }
    }

    @Test
    fun `a task line is read, ticked and moved without a throw, and a move lands on its date`() {
        val random = Random(SEED)
        val lines =
            List(ROUNDS) {
                "    ".repeat(random.nextInt(0, 3)) +
                    listOf("- [ ] ", "- [x] ", "* [-] ", "1. [/] ", "- [", "- ").random(random) +
                    garbage(random, max = 12) +
                    listOf(
                        "",
                        " 📅 2026-10-26",
                        " ⏳ 2026-10-01 🔁 every week",
                        " ✅ 2026-01-01",
                        " 🔁 every 2 days when done",
                    ).random(random)
            }
        lines.forEach { line ->
            TaskLine.isTask(line)
            TaskLine.isOpen(line)
            TaskLine.isRecurring(line)
            TaskLine.indexedText(line)
            TaskLine.complete(line, "2026-10-26")
            TaskLine.completeRecurring(line, "2026-10-26")
            TaskLine.unschedule(line)
            TaskLine.undate(line)
            listOf(' ', '/', 'x', '-').forEach { TaskLine.withStatus(line, it, "2026-10-26") }
            TaskLine.locate(listOf(line, "", line), random.nextInt(-2, 5), line)
            TaskLine.reschedule(line, "2027-01-31")?.let { moved ->
                assertThat(moved.line).contains("2027-01-31")
            }
        }
    }

    @Test
    fun `tasks queries and their expressions answer or say they cannot`() {
        val query =
            listOf(
                "not done",
                "done",
                "due before",
                "due before tomorrow",
                "due in 3",
                "due in 99999999999999999999 days",
                "scheduled 999999999999 days ago",
                "happens in 2026-10-01 2026-10-31",
                "path includes",
                "(due today) OR",
                "NOT (",
                "((((((",
                "sort by",
                "sort by due reverse",
                "group by",
                "limit",
                "limit -1",
                "limit 99999999999999",
                "filter by function",
                "filter by function task.",
                "filter by function [].includes(",
                "filter by function ? :",
                "group by function task.heading ||",
                "{{query.file.path}}",
                "tags include #",
                "description regex matches /(/",
            )
        val tasks =
            listOf(
                QueryTask("a", TaskState.UNCHECKED, "A.md", "", due = "2026-10-26"),
                QueryTask("b", TaskState.CHECKED, "F/B.md", "H", scheduled = "not a date", done = "2026-13-45"),
            )
        val random = Random(SEED)
        val sources =
            query +
                List(
                    ROUNDS,
                ) { List(random.nextInt(1, 4)) { query.random(random) + " " + garbage(random, 6) }.joinToString("\n") }
        sources.forEach { source ->
            val parsed = TasksQuery.parse(source, file = if (random.nextBoolean()) "F/Q.md" else null)
            parsed.run(tasks, LocalDate.of(2026, 10, 26)) { it }
            tasks.forEach { parsed.groupsOf(it) }
            TaskExpression.parse(source)?.let { expression -> tasks.forEach { expression.evaluate(it) } }
            TaskRecurrence.parse(source)
        }
    }

    @Test
    fun `a base expression parses or refuses, and never otherwise throws`() {
        val random = Random(SEED)
        val atoms =
            listOf(
                "file.name",
                "file.mtime",
                "note.x",
                "formula.f",
                "this",
                "1",
                "1.5e309",
                "-",
                "+",
                "*",
                "/",
                "%",
                "(",
                ")",
                "[",
                "]",
                "\"",
                "'",
                ",",
                ".",
                "==",
                "!=",
                "&&",
                "||",
                "!",
                "if(",
                "date(",
                "now()",
                "\"2026-02-30\"",
                "contains(",
                "\"سلام\"",
                "duration(\"1d\")",
                " ",
            )
        repeat(ROUNDS) {
            val source = buildString { repeat(random.nextInt(0, 16)) { append(atoms.random(random)) } }
            try {
                BaseParser.parse(source)
            } catch (_: BaseParseException) {
                // Said above the table; the only way it is allowed to fail.
            }
        }
    }

    @Test
    fun `a base file of any shape is read`() {
        val random = Random(SEED)
        val bases =
            listOf(
                "filters:\n  and:\n    - file.name ==",
                "views:\n  - type: table\n    order: [",
                "formulas:\n  f: \"(\"",
                "views: 3",
                "filters: [1, 2]",
                "views:\n  - limit: -5\n    sort:\n      - property: x\n        direction: sideways",
            )
        (bases + List(ROUNDS) { bases.random(random) + "\n" + garbage(random) }).forEach { Base.parse(it) }
    }

    @Test
    fun `a base of noise runs over a vault of noise, and says what it could not do`() {
        val random = Random(SEED)
        val now = 1_790_000_000_000L
        val notes =
            mapOf(
                "A.md" to ("---\nn: 3\nd: 2026-02-30\nl: [1, [2\ntags: x\n---\n[[B]] #t" to 1),
                "B.md" to ("---\nn: abc\nd: \"\"\n---\n" to 0),
                "سلام.md" to (awkward.random(random) to 5),
            )
        val index = FixtureIndex(now, notes, extra = setOf("x.base", "y.png"))
        val expressions =
            listOf(
                "n / 0",
                "n % 0",
                "n * 1e308 * 10",
                "d - now()",
                "date(d)",
                "d.format(\"YYYY\")",
                "l[99]",
                "l[-1]",
                "file.name.slice(5, 2)",
                "file.name.repeat(-1)",
                "\"a\".matches(\"(\")",
                "number(\"x\")",
                "file.hasLink(this)",
                "file.backlinks.length",
                "list(l).flat()",
                "formula.self",
                "if(n, 1)",
                "duration(\"nonsense\")",
                "max()",
                "min(1, \"a\")",
                "this.file.name",
                "note[\"n\"]",
            )
        repeat(ROUNDS / 5) {
            val filter = expressions.random(random)
            val formula = expressions.random(random)
            val sort = listOf("n", "d", "formula.f", "file.mtime", "x").random(random)
            val source =
                """
                filters: '${filter.replace("'", "''")}'
                formulas:
                  f: '${formula.replace("'", "''")}'
                  self: 'formula.self + 1'
                views:
                  - type: table
                    order: [file.name, formula.f, n]
                    groupBy:
                      property: $sort
                    sort:
                      - property: $sort
                        direction: DESC
                    limit: ${random.nextInt(-3, 5)}
                """.trimIndent()
            val engine = BaseEngine(index, now)
            engine.run(Base.parse(source), "A.md")
        }
    }

    @Test
    fun `wikilinks resolve against any vault without a throw`() {
        val random = Random(SEED)
        val paths = listOf("A.md", "F/A.md", "F/F.md", "سلام.md", "x/y/z.md", ".md", "/", "a b/C D.MD", "")
        val resolver = LinkResolver(paths, mapOf("A.md" to listOf("", " ", "Alias")))
        (inputs().map { it.take(200) } + listOf("../../..", "./", "#", "^", "F/", "//a", "A.md.md")).forEach { target ->
            resolver.resolve(target, paths.random(random))?.let {
                assertThat(Slugs.normalize(it)).isIn(paths.map(Slugs::normalize))
            }
            MarkdownLinks.internal(target)
            MarkdownLinks.decode(target)
            MarkdownLinks.isFile(target)
            MarkdownLinks.fromNote(target, "F/A.md")
        }
    }

    @Test
    fun `contacts, search folding and transclusion read anything`() {
        inputs().forEach { text ->
            Contacts.find(text)
            SearchText.normalize(text)
            SearchText.mapped(text)
            val blocks = MarkdownParser.parseNote(text).blocks
            Transclusion.section(blocks, "a")
            Transclusion.section(blocks, "^x")
            TaskSorter.sort(text)?.let { sorted ->
                // A sort moves lines; it never adds or loses one.
                assertThat(sorted.split("\n").sorted()).isEqualTo(text.split("\n").sorted())
                assertThat(TaskSorter.sort(sorted)).isNull()
            }
        }
    }

    private companion object {
        const val SEED = 50
        const val ROUNDS = 1_500
    }
}
