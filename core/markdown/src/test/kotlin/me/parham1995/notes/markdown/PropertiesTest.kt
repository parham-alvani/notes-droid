package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.PropertyValue.Empty
import me.parham1995.notes.markdown.PropertyValue.Items
import me.parham1995.notes.markdown.PropertyValue.Text
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `properties.md` in notes-linux's `docs/specs/`: the cases the Mac's
 * `PropertiesTests` and Linux's `properties::tests` have, by the same names.
 * Synthetic.
 */
class PropertiesTest {
    private fun form(note: String): PropertySheet {
        val read = PropertySheet.read(note)
        check(read is FrontMatterForm.Form) { "not a form: $read" }
        return read.sheet
    }

    @Test
    fun `a form reads and prints the same bytes`() {
        REPRESENTABLE.forEach { note ->
            val sheet = form(note)
            assertThat(sheet.text()).isEqualTo(sheet.original)
            assertThat(sheet.applied(note)).isEqualTo(note)
            // A value set to what it is changes nothing.
            val same = form(note)
            sheet.properties.forEach { same.set(it.key, it.value) }
            assertThat(same.applied(note)).isEqualTo(note)
        }
    }

    @Test
    fun `YAML the form cannot show stays raw and says why`() {
        val raw =
            listOf(
                "---\n# a comment\ntitle: x\n---\n" to "comment",
                "---\ntitle: x # a comment\n---\n" to "comment",
                "---\nbase: &anchor x\nother: *anchor\n---\n" to "anchor",
                "---\nmap:\n  inner: 1\n---\n" to "map inside a map",
                "---\ntext: |\n  line one\n  line two\n---\n" to "more than one line",
                "---\ntext: >\n  folded\n---\n" to "more than one line",
                "---\ntext: plain\n  goes on\n---\n" to "more than one line",
                "---\ntext: \"open\n  quote\"\n---\n" to "does not close",
                "---\nlist:\n  - a: 1\n---\n" to "map inside a list",
                "---\nlist: [[Link]]\n---\n" to "list inside a list",
                "---\nflow: {a: 1}\n---\n" to "map inside a map",
                "---\ntitle: x\ntitle: y\n---\n" to "twice",
                "---\n- a\n- b\n---\n" to "list",
                "---\ntagged: !!str x\n---\n" to "YAML tag",
                "---\njust text\n---\n" to "not a property",
                "---\nlist:\n  - a\n    more\n---\n" to "more than one line",
                "---\nescape: \"a \\q b\"\n---\n" to "escape",
            )
        raw.forEach { (note, why) ->
            val read = PropertySheet.read(note)
            assertThat(read).isInstanceOf(FrontMatterForm.Source::class.java)
            assertThat((read as FrontMatterForm.Source).reason).contains(why)
        }
    }

    @Test
    fun `a note without front matter gets one when a property is added`() {
        val read = PropertySheet.read("# Title\nBody")
        assertThat(read).isInstanceOf(FrontMatterForm.Absent::class.java)
        val sheet = read.sheet!!
        assertThat(sheet.applied("# Title\nBody")).isEqualTo("# Title\nBody")
        sheet.add("tags", Items(listOf("a")))
        assertThat(sheet.applied("# Title\nBody")).isEqualTo("---\ntags:\n  - a\n---\n# Title\nBody")

        val crlf = PropertySheet.read("Body\r\n").sheet!!
        crlf.add("title", Text("x"))
        assertThat(crlf.applied("Body\r\n")).isEqualTo("---\r\ntitle: x\r\n---\r\nBody\r\n")
        // The note got front matter in the meantime: the edit is refused.
        assertThat(sheet.applied("---\nx: 1\n---\n# Title\nBody")).isNull()
    }

    @Test
    fun `an edit keeps the style of every other line`() {
        val note =
            "---\ntitle: 'Single'\ntags: [one, \"two\"]\naliases:\n    - First\n    - \"Second\"\ncount: 3\n---\n" +
                "# Body stays\ntags: [not, front, matter]"
        val sheet = form(note)
        sheet.set("title", Text("It's new"))
        sheet.set("tags", Items(listOf("one", "two", "three, four")))
        sheet.set("aliases", Items(listOf("Second", "Third")))
        assertThat(sheet.applied(note)).isEqualTo(
            "---\ntitle: 'It''s new'\ntags: [one, \"two\", \"three, four\"]\n" +
                "aliases:\n    - \"Second\"\n    - Third\ncount: 3\n---\n# Body stays\ntags: [not, front, matter]",
        )
    }

    @Test
    fun `rename, remove and add keep the order`() {
        val note = "---\na: 1\nb: [x, y]\n\nc: 'z'\n---\nbody"
        val sheet = form(note)
        sheet.rename("b", "due date")
        assertThat(sheet.applied(note)).isEqualTo("---\na: 1\ndue date: [x, y]\n\nc: 'z'\n---\nbody")
        assertThat(assertThrows(PropertyFailure::class.java) { sheet.rename("c", "a") })
            .hasMessageThat()
            .isEqualTo("The note has a property “a” already.")
        assertThat(assertThrows(PropertyFailure::class.java) { sheet.add("  ", Empty) })
            .hasMessageThat()
            .isEqualTo("A property needs a name.")
        sheet.rename("c", "key: with colon")
        sheet.remove("a")
        sheet.add("done", Text("true"))
        sheet.add("empty", Empty)
        sheet.add("link", Text("[[Note]]"))
        sheet.add("nothing", Text("null"))
        assertThat(sheet.applied(note)).isEqualTo(
            "---\ndue date: [x, y]\n\n\"key: with colon\": 'z'\ndone: true\nempty:\nlink: \"[[Note]]\"\nnothing: \"null\"\n---\nbody",
        )
        sheet.properties.map { it.key }.forEach(sheet::remove)
        assertThat(sheet.applied(note)).isEqualTo("body")
    }

    @Test
    fun `an edit is refused when the front matter changed on disk`() {
        val note = "---\ntitle: a\n---\nbody"
        val sheet = form(note)
        sheet.set("title", Text("b"))
        assertThat(sheet.applied("---\ntitle: a\n---\nthe body changed, that is fine"))
            .isEqualTo("---\ntitle: b\n---\nthe body changed, that is fine")
        assertThat(sheet.applied("---\ntitle: c\n---\nbody")).isNull()
        assertThat(sheet.applied("body")).isNull()

        // As it is queued: the edit, and the front matter it was read from.
        val set = PropertyEdit.Set("title", Text("b"))
        assertThat(PropertySheet.edit("---\ntitle: a\n---\nnew body", set, sheet.original))
            .isEqualTo("---\ntitle: b\n---\nnew body")
        assertThat(
            assertThrows(PropertyFailure::class.java) {
                PropertySheet.edit("---\ntitle: c\n---\nbody", set, sheet.original)
            },
        ).hasMessageThat().isEqualTo(PropertySheet.CHANGED)
    }

    @Test
    fun `a written value reads back the same`() {
        val values =
            listOf(
                "plain",
                "with: colon",
                "# hash",
                "- dash",
                "-5",
                "it's",
                "say \"hi\"",
                "back\\slash",
                " padded ",
                "a #b",
                "[[Link]]",
                "null",
                "~",
                "{x}",
                "&amp",
                "*star",
                "%",
                "سلام",
                "tab\there",
                "a,b",
                "",
            )
        YamlQuote.entries.forEach { quote ->
            values.filter { it.isNotEmpty() }.forEach { value ->
                listOf(false, true).forEach { flow ->
                    val text = PropertySheet.quoted(value, quote, flow)
                    assertThat(PropertySheet.scalar(text, "k").first).isEqualTo(value)
                }
            }
        }
        val sheet = form("---\nlist: [a]\nblock:\n  - a\n---\n")
        val list = Items(values)
        sheet.set("list", list)
        sheet.set("block", list)
        val again = form(sheet.text())
        assertThat(again.value("list")).isEqualTo(list)
        assertThat(again.value("block")).isEqualTo(list)
    }

    @Test
    fun `types are inferred as Obsidian does`() {
        assertThat(PropertyType.infer("tags", Text("a"))).isEqualTo(PropertyType.TAGS)
        assertThat(PropertyType.infer("Aliases", Items(emptyList()))).isEqualTo(PropertyType.ALIASES)
        assertThat(PropertyType.infer("cssclasses", Text("wide"))).isEqualTo(PropertyType.MULTITEXT)
        assertThat(PropertyType.infer("list", Items(listOf("a")))).isEqualTo(PropertyType.MULTITEXT)
        assertThat(PropertyType.infer("done", Text("true"))).isEqualTo(PropertyType.CHECKBOX)
        assertThat(PropertyType.infer("done", Text("False"))).isEqualTo(PropertyType.CHECKBOX)
        assertThat(PropertyType.infer("count", Text("12"))).isEqualTo(PropertyType.NUMBER)
        assertThat(PropertyType.infer("count", Text("-1.5e3"))).isEqualTo(PropertyType.NUMBER)
        assertThat(PropertyType.infer("day", Text("2026-10-25"))).isEqualTo(PropertyType.DATE)
        assertThat(PropertyType.infer("when", Text("2026-10-25T09:30"))).isEqualTo(PropertyType.DATETIME)
        assertThat(PropertyType.infer("when", Text("2026-10-25 09:30:00"))).isEqualTo(PropertyType.DATETIME)
        assertThat(PropertyType.infer("title", Text("2026 plans"))).isEqualTo(PropertyType.TEXT)
        assertThat(PropertyType.infer("title", Empty)).isEqualTo(PropertyType.TEXT)
    }

    @Test
    fun `types json wins`() {
        // As `.obsidian/types.json` is read: PropertyTypes.parse in core/obsidian.
        val types =
            mapOf(
                "year" to "text",
                "rating" to "number",
                "related" to "multitext",
                "seen" to "checkbox",
                "kind" to "unknown",
            )
        assertThat(PropertyType.infer("year", Text("2026"), types)).isEqualTo(PropertyType.TEXT)
        assertThat(PropertyType.infer("Rating", Text("x"), types)).isEqualTo(PropertyType.NUMBER)
        assertThat(PropertyType.infer("related", Text("[[A]]"), types)).isEqualTo(PropertyType.MULTITEXT)
        assertThat(PropertyType.infer("seen", Empty, types)).isEqualTo(PropertyType.CHECKBOX)
        assertThat(PropertyType.infer("kind", Text("12"), types)).isEqualTo(PropertyType.NUMBER)
    }

    @Test
    fun `dates are written as Obsidian writes them`() {
        val date = PropertyType.date("2026-10-25T09:30")!!
        assertThat(PropertyType.format(date, time = true)).isEqualTo("2026-10-25T09:30")
        assertThat(PropertyType.format(date, time = false)).isEqualTo("2026-10-25")
        assertThat(PropertyType.date("2026-02-30")).isNull()
    }

    /** In UTF-16 units, as Android's text and the Mac's text view count. */
    @Test
    fun `the front matter length`() {
        assertThat(PropertySheet.length("no front matter")).isEqualTo(0)
        assertThat(PropertySheet.length("---\nt: 😀\n---\nbody")).isEqualTo("---\nt: 😀\n---\n".length)
        assertThat(PropertySheet.length("---\nopen")).isEqualTo(0)
    }

    @Test
    fun `an edit goes through one string and back`() {
        val edits =
            listOf(
                PropertyEdit.Set("due date", Text("2026-10-25")),
                PropertyEdit.Set("tags", Items(listOf("a, b", "c"))),
                PropertyEdit.Set("tags", Items(emptyList())),
                PropertyEdit.Add("done", Empty),
                PropertyEdit.Remove("x"),
                PropertyEdit.Rename("a", "b: c"),
            )
        edits.forEach { assertThat(PropertyEdit.decode(it.encode())).isEqualTo(it) }
        assertThat(PropertyEdit.decode("set")).isNull()
        assertThat(PropertyEdit.decode("drop\u001ex")).isNull()
    }

    @Test
    fun `an edit is made to a note or says why not`() {
        val note = "---\ntitle: a\n---\nbody"
        val set = PropertyEdit.Set("title", Text("b"))
        assertThat(PropertySheet.edit(note, set)).isEqualTo("---\ntitle: b\n---\nbody")
        assertThat(
            assertThrows(PropertyFailure::class.java) { PropertySheet.edit(note, PropertyEdit.Add("title", Empty)) },
        ).hasMessageThat()
            .isEqualTo("The note has a property “title” already.")
        assertThat(assertThrows(PropertyFailure::class.java) { PropertySheet.edit("---\n# c\nx: 1\n---\n", set) })
            .hasMessageThat()
            .contains("comment")
        assertThat(PropertySheet.edit("body", PropertyEdit.Add("tags", Items(listOf("x")))))
            .isEqualTo("---\ntags:\n  - x\n---\nbody")
    }

    @Test
    fun `names are ranked and completed`() {
        val names = PropertyNames.ranked(listOf("tags", "status", "tags", "Status", "due", "tags", "status"))
        assertThat(names).containsExactly("tags", "status", "due", "Status").inOrder()
        assertThat(PropertyNames.complete("st", names, 8)).containsExactly("status", "Status").inOrder()
        assertThat(PropertyNames.complete("u", names, 8)).containsExactly("status", "due", "Status").inOrder()
        assertThat(PropertyNames.complete("", names, 2)).containsExactly("tags", "status").inOrder()
    }

    private companion object {
        val REPRESENTABLE =
            listOf(
                "---\ntitle: Plain\n---\nBody",
                "---\ntitle: 'single ''quoted'''\nother: \"double \\\"quoted\\\" \\\\ x\"\n---\n",
                "---\ntags: [a, \"b c\", 'd']\naliases: []\n---\n# Heading\n",
                "---\ntags:\n  - a\n  - b\naliases:\n- one\n- 'two'\n---\n",
                "---\ntags:\n    -   spaced\n    -   items\n---\n",
                "---\nempty:\nnext: 1\n---\n",
                "---\n\ntitle: blank lines kept\n\n\nother: x\n\n---\n",
                "---\r\ntitle: crlf\r\ntags:\r\n  - a\r\n---\r\nBody\r\n",
                "---\ntitle: no newline after the fence\n---",
                "---\n---\nNo properties",
                "---\ndone: true\ncount: 12\nratio: -0.5\nday: 2026-10-25\nwhen: 2026-10-25T09:30\nup: \"[[Parent]]\"\n---\n",
                "---\n\"quoted key\": v\n'single key': w\ndue date: 2026-01-01\n---\n",
                "---\nurl: https://example.com/a?b=c#frag\ntime: 09:30\n---\n",
                "---\nعنوان: سلام دنیا\nتگ‌ها: [کار, خانه]\n---\nمتن\n",
                "---\ntitle: trailing spaces   \n...\nBody with --- in it\n---\n",
                "---\nlist:\n  -\n  - x\n---\n",
            )
    }
}
