package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Ported from the Mac app's `BasesTests`, so the two read a base the same way.
 *
 * Every fixture here is made up. The repository is public; the vault is not.
 */
class BasesTest {
    @Test
    fun `yaml reads block maps, lists and scalars`() {
        val node =
            Yaml.parse(
                """
                filters:
                  and:
                    - file.inFolder("Shops")
                    - 'tags.contains("x: y")'
                formulas:
                  shop: file.folder.split("/")[1]
                views:
                  - type: table
                    name: All # a comment
                    order:
                      - file.name
                    limit: 5
                empty:
                flow: [a, "b, c"]
                text: |
                  one
                  two
                """.trimIndent(),
            )
        assertThat(node["filters"]?.get("and")?.items?.map { it.string })
            .containsExactly("file.inFolder(\"Shops\")", "tags.contains(\"x: y\")")
            .inOrder()
        assertThat(node["formulas"]?.get("shop")?.string).isEqualTo("file.folder.split(\"/\")[1]")
        val view = node["views"]?.items?.first()
        assertThat(view?.get("name")?.string).isEqualTo("All")
        assertThat(view?.get("order")?.items?.map { it.string }).containsExactly("file.name")
        assertThat(view?.get("limit")?.string).isEqualTo("5")
        assertThat(node["empty"]).isEqualTo(YamlNode.Null)
        assertThat(node["flow"]?.items?.map { it.string }).containsExactly("a", "b, c").inOrder()
        assertThat(node["text"]?.string).isEqualTo("one\ntwo")
    }

    @Test
    fun `yaml reads a list under a key at the same indent`() {
        val node = Yaml.parse("order:\n- a\n- b\nnext: 1\n")
        assertThat(node["order"]?.items?.map { it.string }).containsExactly("a", "b").inOrder()
        assertThat(node["next"]?.string).isEqualTo("1")
    }

    /** 2026-10-25 12:00, in UTC so the test means the same everywhere. */
    private val now = LocalDateTime.of(2026, 10, 25, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val index =
        FixtureIndex(
            now,
            mapOf(
                "Shops/Bakery/Bakery.md" to
                    (
                        "---\nstage: open\nopened: 2026-10-20\nnext: 2026-10-27T09:30\n" +
                            "roles:\n  - baker\n---\nSee [[Cafe]]." to 2
                    ),
                "Shops/Bakery/Ovens.md" to ("Ovens of [[Bakery]] #kit/ovens" to 40),
                "Shops/Cafe/Cafe.md" to
                    ("---\nstage: closed\nopened: 2026-09-01\nclosed: 2026-10-11\nrating: 4.5\n---\n" to 10),
                "Shops/CLAUDE.md" to ("rules" to 1),
                "Home.md" to ("[[Bakery]] and [[Cafe]]" to 100),
            ),
            extra = setOf("uploads/map.png"),
        )

    private fun engine(source: FixtureIndex = index) =
        BaseEngine(source, now, mapOf("roles" to "multitext", "next" to "datetime"), ZoneOffset.UTC)

    private fun evaluate(
        source: String,
        path: String = "Shops/Bakery/Bakery.md",
    ): String {
        val engine = engine()
        return engine.display(engine.evaluate(BaseParser.parse(source), BaseEngine.Row(path), emptyMap()))
    }

    @Test
    fun `file properties`() {
        assertThat(evaluate("file.name")).isEqualTo("Bakery.md")
        assertThat(evaluate("file.basename")).isEqualTo("Bakery")
        assertThat(evaluate("file.ext")).isEqualTo("md")
        assertThat(evaluate("file.folder")).isEqualTo("Shops/Bakery")
        assertThat(evaluate("file.folder.split(\"/\")[1]")).isEqualTo("Bakery")
        assertThat(evaluate("file.backlinks.length")).isEqualTo("2")
        assertThat(evaluate("file.inFolder(\"Shops\") && !file.inFolder(\"Shop\")")).isEqualTo("true")
        assertThat(evaluate("file.hasProperty(\"stage\")")).isEqualTo("true")
        assertThat(evaluate("file.hasTag(\"kit\")", "Shops/Bakery/Ovens.md")).isEqualTo("true")
        assertThat(evaluate("file.hasLink(\"Cafe\")")).isEqualTo("true")
        assertThat(evaluate("file.name", "Home.md")).isEqualTo("Home.md")
        assertThat(evaluate("file.folder", "Home.md")).isEqualTo("/")
    }

    @Test
    fun `properties and operators`() {
        assertThat(evaluate("stage == \"open\"")).isEqualTo("true")
        assertThat(evaluate("note.stage")).isEqualTo("open")
        assertThat(evaluate("roles.contains(\"baker\")")).isEqualTo("true")
        assertThat(evaluate("[\"open\", \"new\"].contains(stage)")).isEqualTo("true")
        assertThat(evaluate("rating * 2", "Shops/Cafe/Cafe.md")).isEqualTo("9")
        assertThat(evaluate("(closed - opened) / 86400000", "Shops/Cafe/Cafe.md")).isEqualTo("40")
        assertThat(evaluate("((closed - opened) / 86400000).round()", "Shops/Cafe/Cafe.md")).isEqualTo("40")
        assertThat(evaluate("missing")).isEqualTo("")
        assertThat(evaluate("missing.lower()")).isEqualTo("")
        assertThat(evaluate("if(missing, \"yes\", \"no\")")).isEqualTo("no")
        assertThat(evaluate("if(stage == \"open\", \"1 Open\", if(stage == \"closed\", \"2 Closed\", \"3\"))"))
            .isEqualTo("1 Open")
        assertThat(evaluate("\"a\" + 1 + 2")).isEqualTo("a12")
        assertThat(evaluate("1 + 2 * 3 - -1")).isEqualTo("8")
        assertThat(evaluate("10 % 4")).isEqualTo("2")
    }

    @Test
    fun dates() {
        assertThat(evaluate("next >= today()")).isEqualTo("true")
        assertThat(evaluate("opened < today() - \"3 days\"")).isEqualTo("true")
        assertThat(evaluate("file.mtime > now() - \"30 days\"")).isEqualTo("true")
        assertThat(evaluate("file.mtime > now() - \"30 days\"", "Shops/Bakery/Ovens.md")).isEqualTo("false")
        assertThat(evaluate("next.format(\"ddd D MMM, HH:mm\")")).isEqualTo("Tue 27 Oct, 09:30")
        assertThat(evaluate("next.format(\"YYYY-MM-DD [at] h A, Do\")")).isEqualTo("2026-10-27 at 9 AM, 27th")
        assertThat(evaluate("next.relative()")).isEqualTo("in 2 days")
        assertThat(evaluate("opened.relative()")).isEqualTo("6 days ago")
        assertThat(evaluate("file.mtime.relative()", "Home.md")).isEqualTo("3 months ago")
        assertThat(evaluate("date(\"2024-12-01\") + \"1M\" + \"4h\"")).isEqualTo("2025-01-01 04:00")
        assertThat(evaluate("opened")).isEqualTo("2026-10-20")
        assertThat(evaluate("opened.year")).isEqualTo("2026")
    }

    @Test
    fun `strings, lists and regular expressions`() {
        assertThat(evaluate("\"a-b-c\".split(\"-\", 2).join(\"+\")")).isEqualTo("a+b")
        assertThat(evaluate("\"Hello world\".replace(/o/g, \"0\")")).isEqualTo("Hell0 w0rld")
        assertThat(evaluate("\"Hello world\".replace(/o/, \"0\")")).isEqualTo("Hell0 world")
        assertThat(evaluate("\"hello big world\".title()")).isEqualTo("Hello Big World")
        assertThat(evaluate("\"abcdef\".slice(1, -1)")).isEqualTo("bcde")
        assertThat(evaluate("[3, 1, 2].sort().reverse()")).isEqualTo("3, 2, 1")
        assertThat(evaluate("[1, 2, 3].filter(value > 1).map(value * 10)")).isEqualTo("20, 30")
        assertThat(evaluate("[1, 2, 3].reduce(acc + value, 0)")).isEqualTo("6")
        assertThat(evaluate("[1, 1, 2].unique().length")).isEqualTo("2")
        assertThat(evaluate("/ak/.matches(file.name)")).isEqualTo("true")
        assertThat(evaluate("(5).toFixed(2)")).isEqualTo("5.00")
        assertThat(evaluate("(2.5).round()")).isEqualTo("3")
    }

    @Test
    fun `bad expressions are errors`() {
        listOf("a ==", "\"open", "a b").forEach { source ->
            assertThrows(BaseParseException::class.java) { BaseParser.parse(source) }
        }
    }

    @Test
    fun spans() {
        assertThat(BaseSpan.parse("30 days")).isEqualTo(BaseSpan(milliseconds = 30 * 86_400_000.0))
        assertThat(BaseSpan.parse("1M 4h")).isEqualTo(BaseSpan(months = 1, milliseconds = 4 * 3_600_000.0))
        assertThat(BaseSpan.parse("2y")).isEqualTo(BaseSpan(months = 24))
        assertThat(BaseSpan.parse("soon")).isNull()
    }

    private val source =
        """
        filters:
          and:
            - file.inFolder("Shops")
            - file.ext == "md"
            - file.basename != "CLAUDE"
        formulas:
          shop: file.folder.split("/")[1]
          label: if(stage == "open", "1 Open", "2 " + formula.shop)
          loop: formula.loop + 1
        properties:
          formula.shop:
            displayName: Shop
        views:
          - type: table
            name: Shops
            filters:
              and:
                - file.basename == formula.shop
            order:
              - file.name
              - formula.shop
              - rating
            sort:
              - property: file.mtime
                direction: DESC
          - type: table
            name: Grouped
            groupBy:
              property: formula.shop
              direction: DESC
            order:
              - file.name
              - formula.label
              - roles
            sort:
              - property: file.name
                direction: ASC
            limit: 2
          - type: cards
            name: Broken
            filters:
              or:
                - stage ==
                - formula.loop > 0
        """.trimIndent()

    @Test
    fun `runs the views`() {
        val engine = engine()
        val tables = engine.run(Base.parse(source), "Shops/Shops.base")
        assertThat(tables.map { it.name }).containsExactly("Shops", "Grouped", "Broken").inOrder()

        val shops = tables[0]
        assertThat(shops.columns).containsExactly("file name", "Shop", "rating").inOrder()
        assertThat(shops.groups).hasSize(1)
        assertThat(shops.groups[0].name).isNull()
        // Newest first: the bakery changed two days ago, the cafe ten.
        assertThat(
            shops.groups[0].rows.map {
                it.path
            },
        ).containsExactly("Shops/Bakery/Bakery.md", "Shops/Cafe/Cafe.md").inOrder()
        val cafe = shops.groups[0].rows[1].cells
        assertThat(cafe[0]).isEqualTo(BaseCell(listOf(BaseCell.Part("Cafe", "Shops/Cafe/Cafe.md"))))
        assertThat(cafe[1].text).isEqualTo("Cafe")
        assertThat(cafe[2]).isEqualTo(BaseCell(listOf(BaseCell.Part("4.5")), numeric = true))

        // The limit keeps the first two by name, then the groups sort Z to A.
        val grouped = tables[1]
        assertThat(grouped.count).isEqualTo(2)
        assertThat(grouped.groups.map { it.name }).containsExactly("Cafe", "Bakery").inOrder()
        assertThat(grouped.groups.map { it.rows[0].cells[1].text }).containsExactly("2 Cafe", "1 Open").inOrder()
        assertThat(
            grouped.groups[1]
                .rows[0]
                .cells[2]
                .text,
        ).isEqualTo("baker")

        // A statement that does not parse is not applied, and is reported; so
        // is a formula that uses itself.
        assertThat(tables[2].type).isEqualTo("cards")
        assertThat(engine.problems.any { it.contains("stage ==") }).isTrue()
        assertThat(engine.problems.any { it.contains("loop uses itself") }).isTrue()
    }

    @Test
    fun `a base with no views has one`() {
        val tables = engine().run(Base.parse("filters: file.name == \"Home.md\""), "")
        assertThat(tables).hasSize(1)
        assertThat(tables[0].groups[0].rows.map { it.path }).containsExactly("Home.md")
    }

    @Test
    fun `a column id is a property, not a sum`() {
        val notes =
            FixtureIndex(
                now,
                mapOf(
                    "A.md" to ("---\ndate-created: 2026-10-01\nmy-key: b\n---\n" to 0),
                    "B.md" to ("---\ndate-created: 2026-09-01\nmy-key: a\n---\n" to 0),
                ),
            )
        val base =
            Base.parse(
                """
                views:
                  - type: table
                    name: All
                    order:
                      - file.name
                      - note.date-created
                      - my-key
                    sort:
                      - property: my-key
                        direction: ASC
                """.trimIndent(),
            )
        val table = BaseEngine(notes, now, zone = ZoneOffset.UTC).run(base, "")[0]
        assertThat(table.groups[0].rows.map { it.path }).containsExactly("B.md", "A.md").inOrder()
        assertThat(
            table.groups[0]
                .rows[0]
                .cells
                .map { it.text },
        ).containsExactly("B", "2026-09-01", "a").inOrder()
    }

    @Test
    fun `bad numbers are null, not a crash`() {
        listOf(
            "[1, 2][0 / 0]",
            "\"ab\"[1 / 0]",
            "[1, 2][100000000000000000000000]",
            "(1.5).round(1 / 0)",
            "(1.5).toFixed(0 / 0)",
            "\"ab\".repeat(1 / 0)",
            "\"a-b\".split(\"-\", -1)",
            "\"a-b\".split(\"-\", 0 / 0)",
            "duration(\"1M\") * (1 / 0)",
            "\"abc\".slice(0 / 0, 1 / 0)",
            "[1, 2, 3].slice(-100000000000000000000000)",
            "duration(\"99999999999999999999 years\")",
        ).forEach { evaluate(it) }
        assertThat(evaluate("[1, 2][0 / 0]")).isEqualTo("")
        assertThat(evaluate("\"a-b\".split(\"-\", -1).length")).isEqualTo("2")
        assertThat(evaluate("\"abc\".slice(0 / 0, 1 / 0)")).isEqualTo("abc")
        assertThat(BaseSpan.parse("99999999999999999999 years")).isNull()
    }

    @Test
    fun `text sorts the way a person counts`() {
        assertThat(BaseEngine.naturalCompare("Item 9", "Item 10")).isLessThan(0)
        assertThat(BaseEngine.naturalCompare("apple", "Banana")).isLessThan(0)
        assertThat(BaseEngine.naturalCompare("same", "same")).isEqualTo(0)
    }
}

/** A vault of a few notes, parsed the way the indexer parses them. */
internal class FixtureIndex(
    now: Long,
    /** Each note's text and how many days ago it last changed. */
    notes: Map<String, Pair<String, Int>>,
    extra: Set<String> = emptySet(),
) : BaseIndex {
    private val parsed = notes.mapValues { (_, value) -> MarkdownParser.parseNote(value.first) { it } }
    private val properties = notes.mapValues { (_, value) -> FrontMatter.parse(value.first) }
    private val modified = notes.mapValues { (_, value) -> now - value.second * 86_400_000L }
    private val resolver = LinkResolver(notes.keys)

    override val files: Collection<String> = notes.keys + extra

    override fun note(path: String): BaseNote? {
        val note = parsed[path] ?: return null
        return BaseNote(
            properties = properties.getValue(path),
            tags = note.tags,
            links =
                note.links
                    .filter { it.kind == LinkKind.WIKILINK || it.kind == LinkKind.WIKI_EMBED }
                    .map { it.rawTarget to (it.kind == LinkKind.WIKI_EMBED) },
        )
    }

    override fun stat(path: String): BaseStat? = modified[path]?.let { BaseStat(modified = it) }

    override fun resolve(
        target: String,
        from: String,
    ): String? = resolver.resolve(target, from)

    override fun backlinks(path: String): List<String> =
        parsed.keys.filter { source -> note(source)!!.links.any { resolve(it.first, source) == path } }
}
