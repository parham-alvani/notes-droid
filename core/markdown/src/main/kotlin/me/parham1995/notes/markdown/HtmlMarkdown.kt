package me.parham1995.notes.markdown

/**
 * Rich text from a web page or a document, as Markdown, the way Obsidian
 * converts a paste: headings, emphasis, links, pictures, lists, quotes, code
 * and tables keep their meaning; colours and fonts do not.
 *
 * A port of the Mac's `HTMLMarkdown`, case for case. The Mac reads the HTML
 * with the system's tidying XML parser; there is none on the JVM worth a
 * dependency, so [HtmlTree] is a small forgiving one -- enough for what a
 * browser or Google Docs puts on the clipboard, and never a throw on what it
 * does not understand.
 */
object HtmlMarkdown {
    /** The tags that make a paste worth converting. Code editors copy coloured `div` and `span` lines, which stay plain text. */
    private val MEANINGFUL =
        setOf(
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "a",
            "strong",
            "b",
            "em",
            "i",
            "ul",
            "ol",
            "li",
            "table",
            "blockquote",
            "pre",
            "code",
            "img",
            "p",
            "del",
            "s",
            "strike",
            "hr",
        )

    private val BLOCK_NAMES =
        setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table", "blockquote", "pre", "hr", "section")

    /** [html] as Markdown, or null when it holds nothing a plain paste would lose. */
    fun convert(html: String): String? {
        val root = HtmlTree.parse(html)
        val body = root.find("body") ?: root
        if (!hasMeaning(body)) return null
        val writer = Writer()
        writer.block(body, depth = 0)
        return writer.finish().takeIf { it.isNotEmpty() }
    }

    private fun hasMeaning(node: HtmlTree.Element): Boolean {
        if (node.name in MEANINGFUL) return true
        val style = node.attr("style")?.lowercase().orEmpty()
        if (style.contains("font-weight:700") ||
            style.contains("font-weight: 700") ||
            style.contains("font-weight:bold") ||
            style.contains("font-style:italic") ||
            style.contains("font-style: italic")
        ) {
            return true
        }
        return node.children.any { it is HtmlTree.Element && hasMeaning(it) }
    }

    private class Writer {
        val blocks = mutableListOf<String>()

        fun finish(): String =
            blocks
                .map { it.trim('\n', '\r') }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")

        /** The block elements of [node], each a paragraph of its own. */
        fun block(
            node: HtmlTree.Element,
            depth: Int,
        ) {
            val inline = StringBuilder()

            fun flush() {
                val text = tidy(inline.toString())
                if (text.isNotEmpty()) blocks += text
                inline.clear()
            }
            for (child in node.children) {
                if (child !is HtmlTree.Element) {
                    inline.append(inline(child))
                    continue
                }
                when (val name = child.name) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        flush()
                        blocks += "#".repeat(name[1] - '0') + " " + tidy(inline(child))
                    }

                    "p" -> {
                        flush()
                        blocks += tidy(inline(child))
                    }

                    "ul", "ol" -> {
                        flush()
                        blocks += list(child, ordered = name == "ol", depth = 0)
                    }

                    "blockquote" -> {
                        flush()
                        val inner = Writer()
                        inner.block(child, depth + 1)
                        blocks +=
                            inner
                                .finish()
                                .split("\n")
                                .joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
                    }

                    "pre" -> {
                        flush()
                        blocks += "```" + language(child) + "\n" + child.text().trim('\n', '\r') + "\n```"
                    }

                    "hr" -> {
                        flush()
                        blocks += "---"
                    }

                    "table" -> {
                        flush()
                        table(child)?.let { blocks += it }
                    }

                    "div", "section", "article", "main", "header", "footer", "figure", "body", "center", "html" -> {
                        flush()
                        block(child, depth)
                    }

                    "head", "style", "script", "meta", "title", "link" -> Unit

                    else ->
                        if (holdsBlocks(child)) {
                            // Google Docs wraps the whole paste in a `<b>` of normal weight.
                            flush()
                            block(child, depth)
                        } else {
                            inline.append(inline(child))
                        }
                }
            }
            flush()
        }

        fun holdsBlocks(node: HtmlTree.Element): Boolean =
            node.children.any {
                it is HtmlTree.Element &&
                    it.name in BLOCK_NAMES
            }

        /** The text of an inline node, with its marks. */
        fun inline(node: HtmlTree.Node): String {
            if (node is HtmlTree.Text) return node.value.replace('\n', ' ')
            val element = node as HtmlTree.Element
            val inner = element.children.joinToString("") { inline(it) }

            // The mark hugs the words, so `** bold**` does not happen.
            fun mark(marker: String): String {
                val trimmed = inner.trim(' ')
                if (trimmed.isEmpty()) return inner
                val lead = inner.takeWhile { it == ' ' }
                val trail = inner.takeLastWhile { it == ' ' }
                return lead + marker + trimmed + marker + trail
            }
            val style =
                element
                    .attr("style")
                    ?.lowercase()
                    ?.replace(" ", "")
                    .orEmpty()
            return when (element.name) {
                "strong", "b" ->
                    if (style.contains("font-weight:normal") || style.contains("font-weight:400")) inner else mark("**")

                "em", "i" -> mark("*")
                "del", "s", "strike" -> mark("~~")
                "mark" -> mark("==")
                "code" -> "`" + element.text() + "`"
                "br" -> "\n"
                "a" -> {
                    val href = element.attr("href")
                    if (href.isNullOrEmpty() || href.startsWith("javascript:")) {
                        inner
                    } else {
                        val label = inner.trim(' ')
                        if (label.isEmpty() || label == href) "<$href>" else "[$label](${href.replace(" ", "%20")})"
                    }
                }

                "img" -> {
                    val src = element.attr("src")
                    if (src == null || src.startsWith("data:")) "" else "![${element.attr("alt").orEmpty()}]($src)"
                }

                "span" -> {
                    var text = inner
                    if (style.contains("font-weight:700") || style.contains("font-weight:bold")) text = mark("**")
                    if (style.contains("font-style:italic")) text = if (text == inner) mark("*") else "*$text*"
                    text
                }

                "script", "style", "head" -> ""
                else -> inner
            }
        }

        fun list(
            node: HtmlTree.Element,
            ordered: Boolean,
            depth: Int,
        ): String {
            val lines = mutableListOf<String>()
            var number = node.attr("start")?.trim()?.toIntOrNull() ?: 1
            for (item in node.children) {
                if (item !is HtmlTree.Element || item.name != "li") continue
                val text = StringBuilder()
                val nested = mutableListOf<String>()
                for (child in item.children) {
                    val name = (child as? HtmlTree.Element)?.name
                    when (name) {
                        "ul", "ol" -> nested += list(child, ordered = name == "ol", depth = depth + 1)
                        "p" -> text.append(if (text.isEmpty()) "" else " ").append(inline(child))
                        else -> text.append(inline(child))
                    }
                }
                val indent = "    ".repeat(depth)
                // A checkbox from a task list.
                val box =
                    item.children.firstOrNull {
                        it is HtmlTree.Element && it.name == "input" && it.attr("type")?.lowercase() == "checkbox"
                    } as? HtmlTree.Element
                val marker =
                    when {
                        box != null -> "- [${if (box.attr("checked") == null) " " else "x"}] "
                        ordered -> "$number. "
                        else -> "- "
                    }
                lines += indent + marker + tidy(text.toString())
                lines += nested
                number++
            }
            return lines.joinToString("\n")
        }

        fun table(node: HtmlTree.Element): String? {
            val rows =
                node
                    .descendants("tr")
                    .map { row ->
                        row.children
                            .filterIsInstance<HtmlTree.Element>()
                            .filter { it.name == "td" || it.name == "th" }
                            .map { tidy(inline(it)).replace("|", "\\|").replace("\n", " ") }
                    }.filter { it.isNotEmpty() }
            val first = rows.firstOrNull() ?: return null
            val width = rows.maxOf { it.size }

            fun line(cells: List<String>) = "| " + (cells + List(width - cells.size) { "" }).joinToString(" | ") + " |"
            return (listOf(line(first), "|" + " --- |".repeat(width)) + rows.drop(1).map(::line)).joinToString("\n")
        }

        fun language(pre: HtmlTree.Element): String {
            val classes =
                (listOf(pre) + pre.children.filterIsInstance<HtmlTree.Element>())
                    .mapNotNull { it.attr("class") }
                    .joinToString(" ")
            return LANGUAGE
                .find(classes)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        }

        /** Runs of spaces as one, and no space at the ends of a line. */
        fun tidy(text: String): String =
            text
                .replace(SPACES, " ")
                .split("\n")
                .joinToString("\n") { it.trim(' ', '\t', '\u00a0') }
                .trim()
    }

    private val LANGUAGE = Regex("""(?:language|lang)-([\w+#-]+)""")
    private val SPACES = Regex("[ \t\u00a0]+")
}

/**
 * A forgiving HTML reader: tags, attributes, text and entities into a tree,
 * with the end tags a browser would infer -- an open `<p>` or `<li>` closed
 * by the next one -- and nothing ever thrown. What it cannot read is text.
 */
internal object HtmlTree {
    sealed interface Node

    class Text(
        val value: String,
    ) : Node

    class Element(
        val name: String,
        private val attributes: Map<String, String>,
    ) : Node {
        val children = mutableListOf<Node>()

        fun attr(key: String): String? = attributes[key]

        /** Every text under this one, as written. */
        fun text(): String =
            buildString {
                fun walk(node: Node) {
                    when (node) {
                        is Text -> append(node.value)
                        is Element -> node.children.forEach(::walk)
                    }
                }
                walk(this@Element)
            }

        /** The first element named [wanted] at or under this one. */
        fun find(wanted: String): Element? {
            if (name == wanted) return this
            for (child in children) (child as? Element)?.find(wanted)?.let { return it }
            return null
        }

        /** Every element named [wanted] under this one, in document order. */
        fun descendants(wanted: String): List<Element> {
            val out = mutableListOf<Element>()

            fun walk(node: Element) {
                for (child in node.children) {
                    if (child !is Element) continue
                    if (child.name == wanted) out += child
                    walk(child)
                }
            }
            walk(this)
            return out
        }
    }

    fun parse(html: String): Element {
        val root = Element("#root", emptyMap())
        val stack = ArrayDeque<Element>().apply { addLast(root) }
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                stack.last().children += Text(decode(text.toString()))
                text.clear()
            }
        }
        var at = 0
        while (at < html.length) {
            val c = html[at]
            if (c != '<') {
                text.append(c)
                at++
                continue
            }
            val next = html.getOrNull(at + 1)
            when {
                html.startsWith("<!--", at) -> {
                    flushText()
                    val end = html.indexOf("-->", at + 4)
                    at = if (end < 0) html.length else end + 3
                }

                next == '!' || next == '?' -> {
                    flushText()
                    val end = html.indexOf('>', at)
                    at = if (end < 0) html.length else end + 1
                }

                next == '/' -> {
                    flushText()
                    val end = html.indexOf('>', at)
                    val name = html.substring(at + 2, if (end < 0) html.length else end).trim().lowercase()
                    at = if (end < 0) html.length else end + 1
                    // Closed only if it is open; a stray end tag is dropped.
                    if (stack.drop(1).any { it.name == name }) {
                        while (stack.size > 1) {
                            if (stack.removeLast().name == name) break
                        }
                    }
                }

                next != null && next.isLetter() -> {
                    flushText()
                    val tag = readTag(html, at)
                    at = tag.end
                    open(stack, tag.name)
                    val element = Element(tag.name, tag.attributes)
                    stack.last().children += element
                    if (tag.name in RAW) {
                        // A script's or a style's `<` is not a tag.
                        val close = html.indexOf("</${tag.name}", at, ignoreCase = true)
                        val stop = if (close < 0) html.length else close
                        element.children += Text(html.substring(at, stop))
                        val gt = if (close < 0) -1 else html.indexOf('>', close)
                        at = if (gt < 0) html.length else gt + 1
                    } else if (tag.name !in VOID && !tag.selfClosing && stack.size < MAX_DEPTH) {
                        stack.addLast(element)
                    }
                }

                else -> {
                    text.append(c)
                    at++
                }
            }
        }
        flushText()
        return root
    }

    /** The end tags a browser infers before [name] opens. */
    private fun open(
        stack: ArrayDeque<Element>,
        name: String,
    ) {
        fun closeOpen(
            what: Set<String>,
            boundary: Set<String>,
        ) {
            val index = stack.indexOfLast { it.name in what || it.name in boundary }
            if (index > 0 && stack[index].name in what) {
                while (stack.size > index) stack.removeLast()
            }
        }
        when (name) {
            "li" -> closeOpen(setOf("li"), setOf("ul", "ol"))
            "tr" -> closeOpen(setOf("tr"), setOf("table", "tbody", "thead", "tfoot"))
            "td", "th" -> closeOpen(setOf("td", "th"), setOf("tr", "table"))
            "dt", "dd" -> closeOpen(setOf("dt", "dd"), setOf("dl"))
            "option" -> closeOpen(setOf("option"), setOf("select"))
        }
        // A block inside an open paragraph ends it.
        if (name in CLOSES_P && stack.last().name == "p") stack.removeLast()
    }

    private class Tag(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
        val end: Int,
    )

    /** The start tag at [start], which is a `<` followed by a letter. */
    private fun readTag(
        html: String,
        start: Int,
    ): Tag {
        var at = start + 1
        val nameStart = at
        while (at < html.length && !html[at].isWhitespace() && html[at] != '>' && html[at] != '/') at++
        val name = html.substring(nameStart, at).lowercase()
        val attributes = mutableMapOf<String, String>()
        var selfClosing = false
        while (at < html.length) {
            val c = html[at]
            when {
                c == '>' -> return Tag(name, attributes, selfClosing, at + 1)
                c.isWhitespace() -> at++
                c == '/' -> {
                    selfClosing = html.getOrNull(at + 1) == '>'
                    at++
                }

                else -> {
                    val keyStart = at
                    while (at < html.length && !html[at].isWhitespace() && html[at] !in "=>/") at++
                    val key = html.substring(keyStart, at).lowercase()
                    while (at < html.length && html[at].isWhitespace()) at++
                    var value = ""
                    if (html.getOrNull(at) == '=') {
                        at++
                        while (at < html.length && html[at].isWhitespace()) at++
                        val quote = html.getOrNull(at)
                        if (quote == '"' || quote == '\'') {
                            val close = html.indexOf(quote, at + 1)
                            val stop = if (close < 0) html.length else close
                            value = html.substring(at + 1, stop)
                            at = if (close < 0) html.length else close + 1
                        } else {
                            val valueStart = at
                            while (at < html.length && !html[at].isWhitespace() && html[at] != '>') at++
                            value = html.substring(valueStart, at)
                        }
                    }
                    if (key.isNotEmpty()) attributes.putIfAbsent(key, decode(value))
                }
            }
        }
        return Tag(name, attributes, selfClosing, html.length)
    }

    /** `&amp;`, `&#39;`, `&#x200c;` and the named ones a clipboard uses; anything else as written. */
    fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.drop(2).toIntOrNull(HEX))
                body.startsWith("#") -> codePoint(body.drop(1).toIntOrNull())
                else -> NAMED[body]
            } ?: match.value
        }
    }

    private fun codePoint(value: Int?): String? =
        value?.takeIf { it in 1..Character.MAX_CODE_POINT && it !in SURROGATES }?.let { String(Character.toChars(it)) }

    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")
    private val SURROGATES = 0xD800..0xDFFF
    private const val HEX = 16

    private val NAMED =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to "\u00a0",
            "mdash" to "\u2014",
            "ndash" to "\u2013",
            "hellip" to "\u2026",
            "lsquo" to "\u2018",
            "rsquo" to "\u2019",
            "ldquo" to "\u201c",
            "rdquo" to "\u201d",
            "copy" to "\u00a9",
            "reg" to "\u00ae",
            "trade" to "\u2122",
            "zwnj" to "\u200c",
            "zwj" to "\u200d",
            "laquo" to "\u00ab",
            "raquo" to "\u00bb",
            "middot" to "\u00b7",
            "bull" to "\u2022",
            "times" to "\u00d7",
            "deg" to "\u00b0",
            "euro" to "\u20ac",
        )

    private val VOID =
        setOf(
            "br",
            "img",
            "hr",
            "meta",
            "link",
            "input",
            "col",
            "area",
            "base",
            "wbr",
            "source",
            "embed",
            "param",
            "track",
        )

    private val RAW = setOf("script", "style", "textarea", "title")

    private val CLOSES_P =
        setOf(
            "p",
            "div",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "ul",
            "ol",
            "table",
            "blockquote",
            "pre",
            "hr",
            "section",
            "article",
            "header",
            "footer",
            "figure",
            "dl",
        )

    /** Deeper than this, tags are read as text's containers no more: the tree stops growing, the words do not. */
    private const val MAX_DEPTH = 200
}
