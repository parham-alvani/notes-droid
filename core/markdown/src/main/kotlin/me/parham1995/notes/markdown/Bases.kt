package me.parham1995.notes.markdown

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * A `.base` file: an Obsidian Bases query over the vault's files. Filters pick
 * the files, formulas add columns, and each view sorts, groups and shows them.
 *
 * A port of the Mac app's `Bases.swift`, so a base reads the same on both.
 */
data class Base(
    val filter: BaseFilter? = null,
    val formulas: List<Pair<String, String>> = emptyList(),
    val displayNames: Map<String, String> = emptyMap(),
    val views: List<View> = listOf(View()),
) {
    data class View(
        val type: String = "table",
        val name: String = "Table",
        val filter: BaseFilter? = null,
        val order: List<String> = emptyList(),
        /** Each a property id and whether it sorts ascending. */
        val sort: List<Pair<String, Boolean>> = emptyList(),
        val groupBy: Pair<String, Boolean>? = null,
        val limit: Int? = null,
    )

    companion object {
        fun parse(source: String): Base {
            val root = Yaml.parse(source)
            val formulas =
                root["formulas"]?.entries.orEmpty().mapNotNull { (key, value) ->
                    value.string?.let {
                        key to
                            it
                    }
                }
            val displayNames =
                root["properties"]
                    ?.entries
                    .orEmpty()
                    .mapNotNull { (key, value) -> value["displayName"]?.string?.let { key to it } }
                    .toMap()
            val views = mutableListOf<View>()
            for (node in root["views"]?.items.orEmpty()) {
                val group = node["groupBy"]
                val groupProperty = group?.get("property")?.string ?: group?.string
                views +=
                    View(
                        type = node["type"]?.string ?: "table",
                        name = node["name"]?.string ?: "View ${views.size + 1}",
                        filter = node["filters"]?.let(BaseFilter::of),
                        order = node["order"]?.items.orEmpty().mapNotNull { it.string },
                        sort =
                            node["sort"]?.items.orEmpty().mapNotNull { item ->
                                item["property"]?.string?.let {
                                    it to (item["direction"]?.string?.uppercase() != "DESC")
                                }
                            },
                        groupBy = groupProperty?.let { it to (group?.get("direction")?.string?.uppercase() != "DESC") },
                        limit = node["limit"]?.string?.toIntOrNull(),
                    )
            }
            return Base(
                filter = root["filters"]?.let(BaseFilter::of),
                formulas = formulas,
                displayNames = displayNames,
                views = views.ifEmpty { listOf(View()) },
            )
        }

        fun isBase(path: String): Boolean = path.lowercase().endsWith(".base")
    }
}

/** `filters:` -- one statement, or `and`, `or` and `not` over a list of them. */
sealed interface BaseFilter {
    data class Statement(
        val source: String,
    ) : BaseFilter

    data class All(
        val items: List<BaseFilter>,
    ) : BaseFilter

    data class AnyOf(
        val items: List<BaseFilter>,
    ) : BaseFilter

    data class NoneOf(
        val items: List<BaseFilter>,
    ) : BaseFilter

    companion object {
        fun of(node: YamlNode): BaseFilter? =
            when (node) {
                is YamlNode.Scalar -> Statement(node.text)
                is YamlNode.Sequence -> All(node.items.mapNotNull(::of))
                is YamlNode.Mapping -> {
                    val parts =
                        node.entries.mapNotNull { (key, value) ->
                            val items = value.items.mapNotNull(::of)
                            when (key) {
                                "and" -> All(items)
                                "or" -> AnyOf(items)
                                "not" -> NoneOf(items)
                                else -> null
                            }
                        }
                    when (parts.size) {
                        0 -> null
                        1 -> parts[0]
                        else -> All(parts)
                    }
                }
                YamlNode.Null -> null
            }
    }
}

/** What a view shows: the columns, then the rows in their groups. */
data class BaseTable(
    val name: String,
    val type: String,
    val columns: List<String>,
    val groups: List<Group>,
    val count: Int,
) {
    data class Group(
        /** Null when the view has no `groupBy`. */
        val name: String?,
        val rows: List<Row>,
    )

    data class Row(
        val path: String,
        val cells: List<BaseCell>,
    )
}

/** One cell as the page draws it: text runs, some of them links. */
data class BaseCell(
    val parts: List<Part> = emptyList(),
    /** A checkbox property. */
    val check: Boolean? = null,
    val numeric: Boolean = false,
) {
    data class Part(
        val text: String,
        val path: String? = null,
    )

    val text: String get() = parts.joinToString("") { it.text }
}

/** What a base needs to know about one note. */
data class BaseNote(
    val properties: List<FrontMatterProperty> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Each link's raw target, and whether it was an embed. */
    val links: List<Pair<String, Boolean>> = emptyList(),
)

/** A file's times, in epoch milliseconds, and size in bytes; each null when unknown. */
data class BaseStat(
    val modified: Long? = null,
    val created: Long? = null,
    val size: Long? = null,
)

/**
 * The vault, as a base sees it. Every path is relative to the vault; [note] is
 * asked only for markdown, and may read the file the first time.
 */
interface BaseIndex {
    val files: Collection<String>

    fun note(path: String): BaseNote?

    fun stat(path: String): BaseStat?

    fun resolve(
        target: String,
        from: String,
    ): String?

    /** The notes that link to [path], each once. */
    fun backlinks(path: String): List<String>
}

/** Every view of a base, and what in it could not be read or worked out. */
data class BaseResult(
    val tables: List<BaseTable>,
    val problems: List<String>,
)

/**
 * Runs a base over a vault index. One engine is for one run: it keeps what it
 * has worked out for each file, so the views share it.
 */
class BaseEngine(
    private val index: BaseIndex,
    private val now: Long = System.currentTimeMillis(),
    /**
     * `.obsidian/types.json`: a property's type, which says whether `2026` is
     * a number or text and whether one value is still a list.
     */
    private val types: Map<String, String> = emptyMap(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val formulas = HashMap<String, BaseExpr>()
    private val rows = HashMap<String, Row>()
    private val parsed = HashMap<String, Result<BaseExpr>>()
    private val notes = HashMap<String, BaseNote?>()
    private val stats = HashMap<String, BaseStat?>()

    /** The file `this` is: the base, or the note that embeds it. */
    private var context = ""

    private val problemList = mutableListOf<String>()

    /** What could not be read or worked out, once each, for the page to list. */
    val problems: List<String> get() = problemList

    internal class Row(
        val path: String,
    ) {
        val formulas = HashMap<String, BaseValue>()
        val working = HashSet<String>()
    }

    /** Every view of [base]; [context] is the file `this` names. */
    fun run(
        base: Base,
        context: String,
    ): List<BaseTable> {
        this.context = context
        for ((name, source) in base.formulas) {
            expression(source, "Formula $name")?.let { formulas[name] = it }
        }
        val candidates = index.files.sorted().filter { passes(base.filter, it) ?: true }
        return base.views.map { table(it, base, candidates) }
    }

    private fun table(
        view: Base.View,
        base: Base,
        candidates: List<String>,
    ): BaseTable {
        var paths = candidates.filter { passes(view.filter, it) ?: true }
        if (view.sort.isNotEmpty()) {
            val keys = view.sort.map { (property, ascending) -> column(property) to ascending }
            val values = paths.associateWith { path -> keys.map { value(it.first, path) } }
            paths =
                paths.sortedWith { a, b ->
                    for ((offset, key) in keys.withIndex()) {
                        val order = order(values.getValue(a)[offset], values.getValue(b)[offset], key.second)
                        if (order != 0) return@sortedWith order
                    }
                    a.compareTo(b)
                }
        }
        view.limit?.takeIf { it >= 0 }?.let { paths = paths.take(it) }
        val order = view.order.ifEmpty { listOf("file.name") }
        val columns = order.map(::column)
        val tableRows =
            paths.map { path ->
                BaseTable.Row(path, order.zip(columns).map { (id, expression) -> cell(id, expression, path) })
            }
        val groups =
            view.groupBy?.let { (property, ascending) ->
                val key = column(property)
                val byName = LinkedHashMap<String, Pair<BaseValue, MutableList<BaseTable.Row>>>()
                for (row in tableRows) {
                    val value = value(key, row.path)
                    val name = if (isEmpty(value)) "" else display(value)
                    byName.getOrPut(name) { value to mutableListOf() }.second += row
                }
                byName.keys
                    .sortedWith { a, b ->
                        val order = order(byName.getValue(a).first, byName.getValue(b).first, ascending)
                        if (order != 0) order else a.compareTo(b)
                    }.map { BaseTable.Group(it.ifEmpty { "None" }, byName.getValue(it).second) }
            } ?: listOf(BaseTable.Group(null, tableRows))
        return BaseTable(
            name = view.name,
            type = view.type,
            columns = order.map { base.displayNames[it] ?: label(it) },
            groups = groups,
            count = tableRows.size,
        )
    }

    // Filters and columns

    /** Null when nothing in [filter] could be applied. */
    internal fun passes(
        filter: BaseFilter?,
        path: String,
    ): Boolean? =
        when (filter) {
            null -> null
            is BaseFilter.Statement ->
                expression(
                    filter.source,
                    "Filter",
                )?.let { truthy(evaluate(it, row(path), emptyMap())) }
            is BaseFilter.All ->
                filter.items.mapNotNull { passes(it, path) }.takeIf { it.isNotEmpty() }?.let {
                    false !in
                        it
                }
            is BaseFilter.AnyOf ->
                filter.items.mapNotNull { passes(it, path) }.takeIf { it.isNotEmpty() }?.let {
                    true in
                        it
                }
            is BaseFilter.NoneOf ->
                filter.items.mapNotNull { passes(it, path) }.takeIf { it.isNotEmpty() }?.let {
                    true !in
                        it
                }
        }

    /**
     * A property id from `order`, `sort` or `groupBy` -- `file.name`,
     * `formula.x`, `note.x` or a bare name -- as an expression. An id is a
     * path, never parsed as one: `note.date-created` is not a subtraction.
     */
    private fun column(id: String): BaseExpr {
        if (id == "file") return BaseExpr.Name("file")
        for (space in listOf("file", "formula", "note")) {
            if (id.startsWith("$space.")) return BaseExpr.Member(BaseExpr.Name(space), id.substring(space.length + 1))
        }
        return BaseExpr.Index(BaseExpr.Name("note"), BaseExpr.Value(BaseValue.Text(id)))
    }

    private fun value(
        expression: BaseExpr,
        path: String,
    ): BaseValue = evaluate(expression, row(path), emptyMap())

    private fun cell(
        id: String,
        expression: BaseExpr,
        path: String,
    ): BaseCell {
        // The name column is the file, as a link to it.
        if (id == "file.name" || id == "file.basename" || id == "file") {
            return BaseCell(listOf(BaseCell.Part(title(path), path)))
        }
        return cellFor(value(expression, path))
    }

    internal fun cellFor(value: BaseValue): BaseCell =
        when (value) {
            BaseValue.Null -> BaseCell()
            is BaseValue.Bool -> BaseCell(check = value.value)
            is BaseValue.Number -> BaseCell(listOf(BaseCell.Part(display(value))), numeric = true)
            is BaseValue.ListValue ->
                BaseCell(
                    value.items.flatMapIndexed { offset, item ->
                        (if (offset > 0) listOf(BaseCell.Part(", ")) else emptyList()) + cellFor(item).parts
                    },
                )
            is BaseValue.Link ->
                BaseCell(listOf(BaseCell.Part(value.display ?: value.path?.let(::title) ?: value.target, value.path)))
            is BaseValue.File -> BaseCell(listOf(BaseCell.Part(title(value.path), value.path)))
            else -> BaseCell(listOf(BaseCell.Part(display(value))))
        }

    private fun title(path: String): String =
        if (path.endsWith(".md")) path.substringAfterLast('/').removeSuffix(".md") else path.substringAfterLast('/')

    private fun expression(
        source: String,
        what: String,
    ): BaseExpr? {
        val result = parsed.getOrPut(source) { runCatching { BaseParser.parse(source) } }
        return result.getOrElse { error ->
            note("$what not understood, so not applied: $source (${error.message})")
            null
        }
    }

    private fun note(problem: String) {
        if (problem !in problemList) problemList += problem
    }

    private fun row(path: String): Row = rows.getOrPut(path) { Row(path) }

    private fun noteAt(path: String): BaseNote? =
        if (notes.containsKey(path)) notes[path] else index.note(path).also { notes[path] = it }

    private fun statOf(path: String): BaseStat? =
        if (stats.containsKey(path)) stats[path] else index.stat(path).also { stats[path] = it }

    // Evaluation

    internal fun evaluate(
        expression: BaseExpr,
        row: Row,
        locals: Map<String, BaseValue>,
    ): BaseValue =
        when (expression) {
            is BaseExpr.Value -> expression.value
            is BaseExpr.ListOf -> BaseValue.ListValue(expression.items.map { evaluate(it, row, locals) })
            is BaseExpr.Name ->
                locals[expression.name] ?: when (expression.name) {
                    "file" -> BaseValue.File(row.path)
                    "this" -> BaseValue.File(context)
                    "note" -> BaseValue.Object(properties(row.path))
                    "formula" -> BaseValue.Object(formulas.keys.sorted().map { it to formula(it, row) })
                    else -> property(expression.name, row.path)
                }
            is BaseExpr.Member -> {
                val receiver = expression.receiver
                if (receiver is BaseExpr.Name && receiver.name !in locals && receiver.name == "formula") {
                    formula(expression.name, row)
                } else if (receiver is BaseExpr.Name && receiver.name !in locals && receiver.name == "note") {
                    property(expression.name, row.path)
                } else {
                    member(evaluate(receiver, row, locals), expression.name)
                }
            }
            is BaseExpr.Index -> {
                val receiver = expression.receiver
                val key = evaluate(expression.key, row, locals)
                if (receiver is BaseExpr.Name &&
                    receiver.name == "note" &&
                    "note" !in locals &&
                    key is BaseValue.Text
                ) {
                    property(key.value, row.path)
                } else {
                    subscript(evaluate(receiver, row, locals), key)
                }
            }
            is BaseExpr.Call -> call(expression.name, expression.arguments, row, locals)
            is BaseExpr.Method ->
                method(evaluate(expression.receiver, row, locals), expression.name, expression.arguments, row, locals)
            is BaseExpr.Unary -> {
                val value = evaluate(expression.operand, row, locals)
                if (expression.symbol == "!") {
                    BaseValue.Bool(!truthy(value))
                } else {
                    when (value) {
                        is BaseValue.Number -> BaseValue.Number(-value.value)
                        is BaseValue.Duration -> BaseValue.Duration(-value.span)
                        else -> BaseValue.Null
                    }
                }
            }
            is BaseExpr.Binary ->
                when (expression.symbol) {
                    "&&" ->
                        BaseValue.Bool(
                            truthy(evaluate(expression.left, row, locals)) &&
                                truthy(evaluate(expression.right, row, locals)),
                        )
                    "||" ->
                        BaseValue.Bool(
                            truthy(evaluate(expression.left, row, locals)) ||
                                truthy(evaluate(expression.right, row, locals)),
                        )
                    else ->
                        binary(
                            expression.symbol,
                            evaluate(expression.left, row, locals),
                            evaluate(expression.right, row, locals),
                        )
                }
        }

    private fun formula(
        name: String,
        row: Row,
    ): BaseValue {
        row.formulas[name]?.let { return it }
        val expression = formulas[name] ?: return BaseValue.Null
        if (!row.working.add(name)) {
            note("Formula $name uses itself")
            return BaseValue.Null
        }
        val value = evaluate(expression, row, emptyMap())
        row.working.remove(name)
        row.formulas[name] = value
        return value
    }

    // Properties

    private fun properties(path: String): List<Pair<String, BaseValue>> =
        noteAt(path)?.properties.orEmpty().map { it.key to convert(it, path) }

    internal fun property(
        key: String,
        path: String,
    ): BaseValue {
        val properties = noteAt(path)?.properties ?: return BaseValue.Null
        val found =
            properties.firstOrNull { it.key == key }
                ?: properties.firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?: return BaseValue.Null
        return convert(found, path)
    }

    /**
     * Front matter is read as text; the type in `types.json`, or the shape of
     * the text, makes it a number, a date, a checkbox, a link or a list.
     */
    private fun convert(
        property: FrontMatterProperty,
        path: String,
    ): BaseValue {
        val type = types[property.key]
        val listType = type in LIST_TYPES
        if (property.values.isEmpty()) {
            return if (listType ||
                property.isList
            ) {
                BaseValue.ListValue(emptyList())
            } else {
                BaseValue.Null
            }
        }
        if (property.values.size > 1 || property.isList || listType) {
            return BaseValue.ListValue(property.values.map { scalar(it, "text", path) })
        }
        return scalar(property.values[0], type, path)
    }

    private fun scalar(
        text: String,
        type: String?,
        path: String,
    ): BaseValue {
        if (text.startsWith("[[") && text.endsWith("]]")) {
            val inner = text.substring(2, text.length - 2)
            val target = inner.substringBefore('|').substringBefore('#')
            val alias = inner.substringAfter('|', "").takeIf { it.isNotEmpty() }
            return BaseValue.Link(target, index.resolve(target, path), alias)
        }
        return when (type) {
            "number" -> text.toDoubleOrNull()?.let(BaseValue::Number) ?: BaseValue.Text(text)
            "checkbox" -> BaseValue.Bool(text.lowercase() == "true")
            "date", "datetime" -> parseDate(text) ?: BaseValue.Text(text)
            "text" -> BaseValue.Text(text)
            else -> {
                val number = text.toDoubleOrNull()
                when {
                    text == "true" || text == "false" -> BaseValue.Bool(text == "true")
                    number != null && (text.first().isDigit() || text.startsWith("-")) -> BaseValue.Number(number)
                    else -> parseDate(text) ?: BaseValue.Text(text)
                }
            }
        }
    }

    /**
     * `2026-10-25`, `2026-10-25T09:30`, `2026-10-25 09:30:00`, in the local
     * time zone unless it ends in `Z`.
     */
    internal fun parseDate(text: String): BaseValue.Date? {
        val match = DATE.matchEntire(text.trim()) ?: return null
        val g = match.groupValues
        val timed = g[4].isNotEmpty()
        return runCatching {
            val local =
                LocalDateTime.of(
                    g[1].toInt(),
                    g[2].toInt(),
                    g[3].toInt(),
                    if (timed) g[4].toInt() else 0,
                    if (timed) g[5].toInt() else 0,
                    if (timed) g[6].toIntOrNull() ?: 0 else 0,
                )
            val at = local.atZone(if (g[7].isNotEmpty()) ZoneOffset.UTC else zone)
            BaseValue.Date(at.toInstant().toEpochMilli(), timed)
        }.getOrNull()
    }

    private fun zoned(epochMillis: Long): ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)

    private fun startOfDay(epochMillis: Long): Long =
        zoned(epochMillis)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    // Members

    private fun member(
        value: BaseValue,
        name: String,
    ): BaseValue =
        when (value) {
            is BaseValue.File -> fileMember(value.path, name)
            is BaseValue.Link ->
                if (value.path != null && name != "length") fileMember(value.path, name) else BaseValue.Null
            is BaseValue.Text ->
                if (name ==
                    "length"
                ) {
                    BaseValue.Number(value.value.length.toDouble())
                } else {
                    BaseValue.Null
                }
            is BaseValue.ListValue ->
                if (name ==
                    "length"
                ) {
                    BaseValue.Number(value.items.size.toDouble())
                } else {
                    BaseValue.Null
                }
            is BaseValue.Date -> {
                val at = zoned(value.epochMillis)
                when (name) {
                    "year" -> at.year
                    "month" -> at.monthValue
                    "day" -> at.dayOfMonth
                    "hour" -> at.hour
                    "minute" -> at.minute
                    "second" -> at.second
                    "millisecond" -> at.nano / NANOS_PER_MS
                    else -> null
                }?.let { BaseValue.Number(it.toDouble()) } ?: BaseValue.Null
            }
            is BaseValue.Duration -> {
                val ms = value.span.approximateMilliseconds
                when (name) {
                    "days" -> ms / BaseSpan.DAY_MS
                    "hours" -> ms / HOUR_MS
                    "minutes" -> ms / MINUTE_MS
                    "seconds" -> ms / SECOND_MS
                    "milliseconds" -> ms
                    else -> null
                }?.let(BaseValue::Number) ?: BaseValue.Null
            }
            is BaseValue.Object -> value.entries.firstOrNull { it.first == name }?.second ?: BaseValue.Null
            else -> BaseValue.Null
        }

    private fun fileMember(
        path: String,
        name: String,
    ): BaseValue {
        val parts = path.split('/')
        val fileName = parts.last()
        val dot = fileName.lastIndexOf('.')
        return when (name) {
            "name" -> BaseValue.Text(fileName)
            "basename" -> BaseValue.Text(if (dot >= 0) fileName.substring(0, dot) else fileName)
            "ext" -> BaseValue.Text(if (dot >= 0) fileName.substring(dot + 1) else "")
            "path" -> BaseValue.Text(path)
            "folder" -> BaseValue.Text(if (parts.size > 1) parts.dropLast(1).joinToString("/") else "/")
            "file" -> BaseValue.File(path)
            "mtime" -> statOf(path)?.modified?.let { BaseValue.Date(it, true) } ?: BaseValue.Null
            "ctime" -> statOf(path)?.created?.let { BaseValue.Date(it, true) } ?: BaseValue.Null
            "size" -> statOf(path)?.size?.let { BaseValue.Number(it.toDouble()) } ?: BaseValue.Null
            "properties" -> BaseValue.Object(properties(path))
            "tags" -> BaseValue.ListValue(noteAt(path)?.tags.orEmpty().map(BaseValue::Text))
            "links", "embeds" -> {
                val wanted = name == "embeds"
                val links =
                    noteAt(path)
                        ?.links
                        .orEmpty()
                        .filter { it.second == wanted }
                        .map { it.first }
                        .distinct()
                BaseValue.ListValue(links.map { BaseValue.Link(it, index.resolve(it, path), null) })
            }
            "backlinks" -> BaseValue.ListValue(index.backlinks(path).distinct().map(BaseValue::File))
            else -> BaseValue.Null
        }
    }

    private fun subscript(
        value: BaseValue,
        key: BaseValue,
    ): BaseValue =
        when {
            value is BaseValue.ListValue && key is BaseValue.Number -> {
                val whole = key.value.whole()
                if (whole == null) {
                    BaseValue.Null
                } else {
                    val at = if (whole < 0) value.items.size + whole else whole
                    value.items.getOrNull(at) ?: BaseValue.Null
                }
            }
            value is BaseValue.Text && key is BaseValue.Number ->
                key.value
                    .whole()
                    ?.let { value.value.getOrNull(it) }
                    ?.let { BaseValue.Text(it.toString()) } ?: BaseValue.Null
            key is BaseValue.Text -> member(value, key.value)
            else -> BaseValue.Null
        }

    // Functions

    private fun call(
        name: String,
        arguments: List<BaseExpr>,
        row: Row,
        locals: Map<String, BaseValue>,
    ): BaseValue {
        if (name == "if") {
            val condition = arguments.firstOrNull() ?: return BaseValue.Null
            if (truthy(evaluate(condition, row, locals))) {
                return arguments.getOrNull(1)?.let { evaluate(it, row, locals) } ?: BaseValue.Null
            }
            return arguments.getOrNull(2)?.let { evaluate(it, row, locals) } ?: BaseValue.Null
        }
        val values = arguments.map { evaluate(it, row, locals) }
        val first = values.firstOrNull() ?: BaseValue.Null
        return when (name) {
            "now" -> BaseValue.Date(now, true)
            "today" -> BaseValue.Date(startOfDay(now), false)
            "date" ->
                when (first) {
                    is BaseValue.Date -> first
                    is BaseValue.Text -> parseDate(first.value) ?: BaseValue.Null
                    else -> BaseValue.Null
                }
            "duration" ->
                if (first is BaseValue.Text) {
                    BaseSpan
                        .parse(
                            first.value,
                        )?.let(BaseValue::Duration) ?: BaseValue.Null
                } else {
                    first
                }
            "number" -> number(first)?.let(BaseValue::Number) ?: BaseValue.Null
            "list" ->
                when {
                    first is BaseValue.ListValue -> first
                    isEmpty(first) && values.isEmpty() -> BaseValue.ListValue(emptyList())
                    else -> BaseValue.ListValue(listOf(first))
                }
            "link" -> {
                val shown = values.getOrNull(1)?.let(::display)
                when (first) {
                    is BaseValue.Text -> BaseValue.Link(first.value, resolve(first.value), shown)
                    is BaseValue.File -> BaseValue.Link(first.path, first.path, shown)
                    is BaseValue.Link -> BaseValue.Link(first.target, first.path, shown)
                    else -> BaseValue.Null
                }
            }
            "file" ->
                when (first) {
                    is BaseValue.Text -> resolve(first.value)?.let(BaseValue::File) ?: BaseValue.Null
                    is BaseValue.Link -> first.path?.let(BaseValue::File) ?: BaseValue.Null
                    is BaseValue.File -> first
                    else -> BaseValue.Null
                }
            "max", "min" -> {
                val numbers = values.mapNotNull(::number)
                if (numbers.isEmpty()) {
                    BaseValue.Null
                } else {
                    BaseValue.Number(if (name == "max") numbers.max() else numbers.min())
                }
            }
            "escapeHTML", "html", "icon", "image" -> BaseValue.Text(display(first))
            "random" -> BaseValue.Number(Math.random())
            else -> {
                note("Unknown function $name()")
                BaseValue.Null
            }
        }
    }

    private fun resolve(target: String): String? = if (target in index.files) target else index.resolve(target, context)

    private fun method(
        receiver: BaseValue,
        name: String,
        arguments: List<BaseExpr>,
        row: Row,
        locals: Map<String, BaseValue>,
    ): BaseValue {
        // These run their argument once per item, with `value` and `index` set.
        val body = arguments.firstOrNull()
        if (receiver is BaseValue.ListValue && name in setOf("filter", "map", "reduce") && body != null) {
            fun scope(
                offset: Int,
                item: BaseValue,
            ) = locals + mapOf("value" to item, "index" to BaseValue.Number(offset.toDouble()))
            return when (name) {
                "filter" ->
                    BaseValue.ListValue(
                        receiver.items.filterIndexed {
                            offset,
                            item,
                            ->
                            truthy(evaluate(body, row, scope(offset, item)))
                        },
                    )
                "map" ->
                    BaseValue.ListValue(
                        receiver.items.mapIndexed {
                            offset,
                            item,
                            ->
                            evaluate(body, row, scope(offset, item))
                        },
                    )
                else -> {
                    var accumulator = arguments.getOrNull(1)?.let { evaluate(it, row, locals) } ?: BaseValue.Null
                    receiver.items.forEachIndexed { offset, item ->
                        accumulator = evaluate(body, row, scope(offset, item) + ("acc" to accumulator))
                    }
                    accumulator
                }
            }
        }
        val values = arguments.map { evaluate(it, row, locals) }
        val first = values.firstOrNull() ?: BaseValue.Null
        // Methods every value has.
        when (name) {
            "isEmpty" -> return BaseValue.Bool(isEmpty(receiver))
            "isTruthy" -> return BaseValue.Bool(truthy(receiver))
            "toString" -> return BaseValue.Text(display(receiver))
            "isType" -> return BaseValue.Bool(typeName(receiver) == display(first).lowercase())
        }
        when (receiver) {
            BaseValue.Null -> return BaseValue.Null
            is BaseValue.Text -> return stringMethod(receiver.value, name, values)
            is BaseValue.Number -> {
                val number = receiver.value
                when (name) {
                    "abs" -> return BaseValue.Number(abs(number))
                    "ceil" -> return BaseValue.Number(Math.ceil(number))
                    "floor" -> return BaseValue.Number(Math.floor(number))
                    "round" -> {
                        val digits = (number(first)?.whole() ?: 0).coerceIn(-MAX_DIGITS, MAX_DIGITS)
                        val scale = TEN.pow(digits)
                        return BaseValue.Number(roundHalfAway(number * scale) / scale)
                    }
                    "toFixed" -> {
                        val digits = (number(first)?.whole() ?: 0).coerceIn(0, MAX_DIGITS)
                        return BaseValue.Text(String.format(Locale.ROOT, "%.${digits}f", number))
                    }
                }
            }
            is BaseValue.Date ->
                when (name) {
                    "format" -> return BaseValue.Text(format(receiver.epochMillis, display(first)))
                    "relative" -> return BaseValue.Text(relative(receiver.epochMillis, now))
                    "date" -> return BaseValue.Date(startOfDay(receiver.epochMillis), false)
                    "time" -> return BaseValue.Text(
                        if (receiver.time) format(receiver.epochMillis, "HH:mm:ss") else "00:00:00",
                    )
                }
            is BaseValue.ListValue -> {
                val items = receiver.items
                when (name) {
                    "contains" -> return BaseValue.Bool(items.any { equal(it, first) })
                    "containsAll" -> return BaseValue.Bool(values.all { value -> items.any { equal(it, value) } })
                    "containsAny" -> return BaseValue.Bool(values.any { value -> items.any { equal(it, value) } })
                    "join" ->
                        return BaseValue.Text(
                            items.joinToString(if (values.isEmpty()) "," else display(first)) { display(it) },
                        )
                    "reverse" -> return BaseValue.ListValue(items.reversed())
                    "flat" -> return BaseValue.ListValue(
                        items.flatMap {
                            (it as? BaseValue.ListValue)?.items
                                ?: listOf(it)
                        },
                    )
                    "sort" -> return BaseValue.ListValue(items.sortedWith { a, b -> order(a, b, true) })
                    "unique" -> {
                        val seen = HashSet<String>()
                        return BaseValue.ListValue(items.filter { seen.add(typeName(it) + display(it)) })
                    }
                    "slice" -> {
                        val range = slice(items.size, values.mapNotNull(::number))
                        return BaseValue.ListValue(items.subList(range.first, range.last + 1))
                    }
                }
            }
            is BaseValue.File, is BaseValue.Link -> {
                val path = (receiver as? BaseValue.File)?.path ?: (receiver as BaseValue.Link).path
                if (path != null) {
                    fileMethod(path, name, values)?.let { return it }
                    if (receiver is BaseValue.Link && receiver.display != null) {
                        return stringMethod(receiver.display, name, values)
                    }
                } else if (receiver is BaseValue.Link) {
                    return stringMethod(receiver.target, name, values)
                }
            }
            is BaseValue.Object ->
                when (name) {
                    "keys" -> return BaseValue.ListValue(receiver.entries.map { BaseValue.Text(it.first) })
                    "values" -> return BaseValue.ListValue(receiver.entries.map { it.second })
                }
            is BaseValue.Pattern ->
                if (name == "matches") return BaseValue.Bool(receiver.regex.containsMatchIn(display(first)))
            else -> Unit
        }
        note("Unknown method .$name() on ${typeName(receiver)}")
        return BaseValue.Null
    }

    private fun stringMethod(
        text: String,
        name: String,
        values: List<BaseValue>,
    ): BaseValue {
        val first = values.firstOrNull() ?: BaseValue.Null
        val strings = values.map(::display)
        return when (name) {
            "contains" -> BaseValue.Bool(text.contains(display(first)))
            "containsAll" -> BaseValue.Bool(strings.all { text.contains(it) })
            "containsAny" -> BaseValue.Bool(strings.any { text.contains(it) })
            "startsWith" -> BaseValue.Bool(text.startsWith(display(first)))
            "endsWith" -> BaseValue.Bool(text.endsWith(display(first)))
            "lower" -> BaseValue.Text(text.lowercase())
            "upper" -> BaseValue.Text(text.uppercase())
            "trim" -> BaseValue.Text(text.trim())
            "reverse" -> BaseValue.Text(text.reversed())
            "repeat" -> {
                val count = maxOf(0, number(first)?.whole() ?: 0)
                // A count too large for memory is bad input, not a crash.
                if (count > MAX_REPEAT / maxOf(1, text.length)) BaseValue.Null else BaseValue.Text(text.repeat(count))
            }
            "title" ->
                BaseValue.Text(
                    buildString {
                        var start = true
                        for (ch in text) {
                            append(if (start) ch.uppercase() else ch.toString())
                            start = ch.isWhitespace()
                        }
                    },
                )
            "slice" -> {
                val range = slice(text.length, values.mapNotNull(::number))
                BaseValue.Text(text.substring(range.first, range.last + 1))
            }
            "split" -> {
                var parts: List<String> =
                    when {
                        first is BaseValue.Pattern -> first.regex.split(text)
                        display(first).isEmpty() -> text.map { it.toString() }
                        else -> text.split(display(first))
                    }
                val limit = values.getOrNull(1)?.let(::number)?.whole()
                if (limit != null && limit >= 0) parts = parts.take(limit)
                BaseValue.ListValue(parts.map(BaseValue::Text))
            }
            "replace" -> {
                val replacement = values.getOrNull(1)?.let(::display).orEmpty()
                if (first is BaseValue.Pattern) {
                    val template = replacement.replace("\\", "\\\\")
                    BaseValue.Text(
                        if (first.global) {
                            first.regex.replace(
                                text,
                                template,
                            )
                        } else {
                            first.regex.replaceFirst(text, template)
                        },
                    )
                } else {
                    BaseValue.Text(text.replace(display(first), replacement))
                }
            }
            else -> {
                note("Unknown method .$name() on string")
                BaseValue.Null
            }
        }
    }

    private fun fileMethod(
        path: String,
        name: String,
        values: List<BaseValue>,
    ): BaseValue? {
        val first = values.firstOrNull() ?: BaseValue.Null
        return when (name) {
            "inFolder" -> {
                val folder = display(first).trim('/')
                BaseValue.Bool(folder.isEmpty() || path.startsWith("$folder/"))
            }
            "hasProperty" -> {
                val key = display(first)
                BaseValue.Bool(noteAt(path)?.properties.orEmpty().any { it.key == key })
            }
            "hasTag" -> {
                val tags = noteAt(path)?.tags.orEmpty().map { it.lowercase() }
                val wanted = values.map { display(it).lowercase().trim('#') }
                BaseValue.Bool(wanted.any { tag -> tags.any { it == tag || it.startsWith("$tag/") } })
            }
            "hasLink", "linksTo" -> {
                val target = targetOf(first) ?: return BaseValue.Bool(false)
                BaseValue.Bool(noteAt(path)?.links.orEmpty().any { index.resolve(it.first, path) == target })
            }
            "asLink" -> BaseValue.Link(path, path, if (values.isEmpty()) null else display(first))
            "asFile" -> BaseValue.File(path)
            else -> null
        }
    }

    /** The vault path a file, a link or a name stands for. */
    private fun targetOf(value: BaseValue): String? =
        when (value) {
            is BaseValue.File -> value.path
            is BaseValue.Link -> value.path
            is BaseValue.Text -> resolve(value.value)
            else -> null
        }

    // Operators

    private fun binary(
        symbol: String,
        left: BaseValue,
        right: BaseValue,
    ): BaseValue =
        when (symbol) {
            "==" -> BaseValue.Bool(equal(left, right))
            "!=" -> BaseValue.Bool(!equal(left, right))
            "<", ">", "<=", ">=" -> {
                val order = compare(left, right)
                BaseValue.Bool(
                    order != null &&
                        when (symbol) {
                            "<" -> order < 0
                            ">" -> order > 0
                            "<=" -> order <= 0
                            else -> order >= 0
                        },
                )
            }
            else -> arithmetic(symbol, left, right)
        }

    private fun arithmetic(
        symbol: String,
        left: BaseValue,
        right: BaseValue,
    ): BaseValue {
        if (left is BaseValue.Date && (symbol == "+" || symbol == "-")) {
            if (right is BaseValue.Date && symbol == "-") {
                return BaseValue.Duration(BaseSpan(milliseconds = (left.epochMillis - right.epochMillis).toDouble()))
            }
            var span =
                span(right)
                    ?: return if (symbol == "+") BaseValue.Text(display(left) + display(right)) else BaseValue.Null
            if (symbol == "-") span = -span
            return BaseValue.Date(add(span, left.epochMillis), left.time || span.milliseconds % BaseSpan.DAY_MS != 0.0)
        }
        if (left is BaseValue.Duration && right is BaseValue.Duration) {
            when (symbol) {
                "+" -> return BaseValue.Duration(left.span + right.span)
                "-" -> return BaseValue.Duration(left.span + -right.span)
            }
        }
        if (left is BaseValue.Duration && right is BaseValue.Number) {
            when (symbol) {
                "*" -> {
                    val months = (left.span.months * right.value).whole() ?: return BaseValue.Null
                    return BaseValue.Duration(BaseSpan(months, left.span.milliseconds * right.value))
                }
                "/" -> return BaseValue.Number(left.span.approximateMilliseconds / right.value)
            }
        }
        if (symbol == "+" && (left is BaseValue.Text || right is BaseValue.Text)) {
            return BaseValue.Text(display(left) + display(right))
        }
        val a = number(left) ?: return BaseValue.Null
        val b = number(right) ?: return BaseValue.Null
        return when (symbol) {
            "+" -> BaseValue.Number(a + b)
            "-" -> BaseValue.Number(a - b)
            "*" -> BaseValue.Number(a * b)
            "/" -> if (b == 0.0) BaseValue.Null else BaseValue.Number(a / b)
            "%" -> if (b == 0.0) BaseValue.Null else BaseValue.Number(a % b)
            else -> BaseValue.Null
        }
    }

    private fun span(value: BaseValue): BaseSpan? =
        when (value) {
            is BaseValue.Duration -> value.span
            is BaseValue.Text -> BaseSpan.parse(value.value)
            is BaseValue.Number -> BaseSpan(milliseconds = value.value)
            else -> null
        }

    private fun add(
        span: BaseSpan,
        epochMillis: Long,
    ): Long {
        val shifted =
            if (span.months == 0) {
                epochMillis
            } else {
                zoned(epochMillis)
                    .plusMonths(span.months.toLong())
                    .toInstant()
                    .toEpochMilli()
            }
        return shifted + span.milliseconds.toLong()
    }

    private fun number(value: BaseValue): Double? =
        when (value) {
            is BaseValue.Number -> value.value
            is BaseValue.Bool -> if (value.value) 1.0 else 0.0
            is BaseValue.Text -> value.value.trim().toDoubleOrNull()
            is BaseValue.Date -> value.epochMillis.toDouble()
            is BaseValue.Duration -> value.span.approximateMilliseconds
            else -> null
        }

    internal fun truthy(value: BaseValue): Boolean =
        when (value) {
            BaseValue.Null -> false
            is BaseValue.Bool -> value.value
            is BaseValue.Number -> value.value != 0.0 && !value.value.isNaN()
            is BaseValue.Text -> value.value.isNotEmpty()
            is BaseValue.ListValue -> value.items.isNotEmpty()
            else -> true
        }

    private fun isEmpty(value: BaseValue): Boolean =
        when (value) {
            BaseValue.Null -> true
            is BaseValue.Text -> value.value.isEmpty()
            is BaseValue.ListValue -> value.items.isEmpty()
            is BaseValue.Object -> value.entries.isEmpty()
            else -> false
        }

    private fun typeName(value: BaseValue): String =
        when (value) {
            BaseValue.Null -> "null"
            is BaseValue.Bool -> "boolean"
            is BaseValue.Number -> "number"
            is BaseValue.Text -> "string"
            is BaseValue.Date -> "date"
            is BaseValue.Duration -> "duration"
            is BaseValue.ListValue -> "list"
            is BaseValue.Link -> "link"
            is BaseValue.File -> "file"
            is BaseValue.Object -> "object"
            is BaseValue.Pattern -> "regexp"
        }

    private fun equal(
        a: BaseValue,
        b: BaseValue,
    ): Boolean =
        when {
            a is BaseValue.Null && b is BaseValue.Null -> true
            a is BaseValue.Bool && b is BaseValue.Bool -> a.value == b.value
            a is BaseValue.Number && b is BaseValue.Number -> a.value == b.value
            a is BaseValue.Text && b is BaseValue.Text -> a.value == b.value
            (a is BaseValue.Date && (b is BaseValue.Date || b is BaseValue.Text)) ||
                (a is BaseValue.Text && b is BaseValue.Date) ||
                (a is BaseValue.Duration && b is BaseValue.Duration) -> compare(a, b) == 0
            a is BaseValue.ListValue && b is BaseValue.ListValue ->
                a.items.size == b.items.size && a.items.zip(b.items).all { (x, y) -> equal(x, y) }
            a is BaseValue.File && b is BaseValue.File -> a.path == b.path
            a is BaseValue.Link && b is BaseValue.File -> a.path != null && a.path == b.path
            a is BaseValue.File && b is BaseValue.Link -> b.path != null && b.path == a.path
            a is BaseValue.Link && b is BaseValue.Link -> if (a.path != null) a.path == b.path else a.target == b.target
            a is BaseValue.Link && b is BaseValue.Text -> linkIs(a, b.value)
            a is BaseValue.Text && b is BaseValue.Link -> linkIs(b, a.value)
            a is BaseValue.File && b is BaseValue.Text -> b.value == a.path || title(a.path) == b.value
            a is BaseValue.Text && b is BaseValue.File -> a.value == b.path || title(b.path) == a.value
            else -> false
        }

    private fun linkIs(
        link: BaseValue.Link,
        text: String,
    ): Boolean = text == link.target || text == link.path || (link.path != null && title(link.path) == text)

    /** Negative, zero or positive; null when the two cannot be ordered. */
    internal fun compare(
        a: BaseValue,
        b: BaseValue,
    ): Int? {
        fun sign(
            x: Double,
            y: Double,
        ) = x.compareTo(y).coerceIn(-1, 1)
        return when {
            a is BaseValue.Number && b is BaseValue.Number -> sign(a.value, b.value)
            a is BaseValue.Date && b is BaseValue.Date -> a.epochMillis.compareTo(b.epochMillis).coerceIn(-1, 1)
            a is BaseValue.Date && b is BaseValue.Text -> parseDate(b.value)?.let { compare(a, it) }
            a is BaseValue.Text && b is BaseValue.Date -> parseDate(a.value)?.let { compare(it, b) }
            a is BaseValue.Duration && b is BaseValue.Duration ->
                sign(a.span.approximateMilliseconds, b.span.approximateMilliseconds)
            a is BaseValue.Duration && b is BaseValue.Number -> sign(a.span.approximateMilliseconds, b.value)
            a is BaseValue.Number && b is BaseValue.Duration -> sign(a.value, b.span.approximateMilliseconds)
            a is BaseValue.Text && b is BaseValue.Text -> naturalCompare(a.value, b.value)
            a is BaseValue.Bool && b is BaseValue.Bool -> a.value.compareTo(b.value)
            a is BaseValue.File && b is BaseValue.File -> naturalCompare(title(a.path), title(b.path))
            a is BaseValue.Link && b is BaseValue.Link ->
                naturalCompare(a.display ?: a.target, b.display ?: b.target)
            a is BaseValue.ListValue && b is BaseValue.ListValue -> {
                for ((p, q) in a.items.zip(b.items)) {
                    val order = compare(p, q)
                    if (order != null && order != 0) return order
                }
                a.items.size
                    .compareTo(b.items.size)
                    .coerceIn(-1, 1)
            }
            else -> null
        }
    }

    /**
     * The order of two rows for a sort key: empty values last both ways, and
     * values of different kinds by their text.
     */
    internal fun order(
        a: BaseValue,
        b: BaseValue,
        ascending: Boolean,
    ): Int {
        val emptyA = isEmpty(a)
        val emptyB = isEmpty(b)
        if (emptyA || emptyB) {
            return if (emptyA == emptyB) {
                0
            } else if (emptyA) {
                1
            } else {
                -1
            }
        }
        val order = compare(a, b) ?: naturalCompare(plain(a), plain(b))
        return if (ascending) order else -order
    }

    private fun plain(value: BaseValue): String =
        when (value) {
            is BaseValue.Text -> value.value
            is BaseValue.Number -> value.value.toString()
            else -> typeName(value)
        }

    private fun slice(
        count: Int,
        bounds: List<Double>,
    ): IntRange {
        fun clamp(value: Double): Int {
            val at = if (value.isNaN()) 0 else value.coerceIn(-count.toDouble(), count.toDouble()).toInt()
            return (if (at < 0) count + at else at).coerceIn(0, count)
        }
        val start = bounds.firstOrNull()?.let(::clamp) ?: 0
        val end = bounds.getOrNull(1)?.let(::clamp) ?: count
        return start until maxOf(start, end)
    }

    // Text

    /** A value as text: in a cell, in `+` with a string, in `join`. */
    fun display(value: BaseValue): String =
        when (value) {
            BaseValue.Null -> ""
            is BaseValue.Bool -> value.value.toString()
            is BaseValue.Number -> {
                val number = value.value
                if (number == Math.rint(number) && abs(number) < WHOLE_LIMIT) {
                    number.toLong().toString()
                } else {
                    String.format(Locale.ROOT, "%.4f", number).trimEnd('0')
                }
            }
            is BaseValue.Text -> value.value
            is BaseValue.Date -> format(value.epochMillis, if (value.time) "YYYY-MM-DD HH:mm" else "YYYY-MM-DD")
            is BaseValue.Duration -> humanize(value.span.approximateMilliseconds / SECOND_MS)
            is BaseValue.ListValue -> value.items.joinToString(", ") { display(it) }
            is BaseValue.Link -> value.display ?: value.path?.let(::title) ?: value.target
            is BaseValue.File -> title(value.path)
            is BaseValue.Object -> value.entries.joinToString(", ") { "${it.first}: ${display(it.second)}" }
            is BaseValue.Pattern -> "/${value.regex.pattern}/"
        }

    /** A date in a moment.js format, such as `ddd D MMM, HH:mm`. */
    fun format(
        epochMillis: Long,
        pattern: String,
    ): String {
        val at = zoned(epochMillis)
        val hour = at.hour
        val twelve = if (hour % 12 == 0) 12 else hour % 12
        val weekday = at.dayOfWeek.value % DAYS_IN_WEEK
        val offset = at.offset.totalSeconds / SECONDS_PER_MINUTE
        val zoneText = (if (offset < 0) "-" else "+") + two(abs(offset) / MINUTES_PER_HOUR)
        return FORMAT_TOKEN.replace(pattern) { match ->
            when (val token = match.value) {
                "YYYY" -> at.year.toString()
                "YY" -> two(at.year % CENTURY)
                "Q" -> ((at.monthValue - 1) / MONTHS_PER_QUARTER + 1).toString()
                "MMMM" -> MONTHS[at.monthValue - 1]
                "MMM" -> MONTHS[at.monthValue - 1].take(SHORT_NAME)
                "MM" -> two(at.monthValue)
                "M" -> at.monthValue.toString()
                "Do" -> ordinal(at.dayOfMonth)
                "DD" -> two(at.dayOfMonth)
                "D" -> at.dayOfMonth.toString()
                "dddd" -> WEEKDAYS[weekday]
                "ddd" -> WEEKDAYS[weekday].take(SHORT_NAME)
                "dd" -> WEEKDAYS[weekday].take(2)
                "d" -> weekday.toString()
                "HH" -> two(hour)
                "H" -> hour.toString()
                "hh" -> two(twelve)
                "h" -> twelve.toString()
                "mm" -> two(at.minute)
                "m" -> at.minute.toString()
                "ss" -> two(at.second)
                "s" -> at.second.toString()
                "SSS" -> (at.nano / NANOS_PER_MS).toString().padStart(SHORT_NAME, '0')
                "A" -> if (hour < NOON) "AM" else "PM"
                "a" -> if (hour < NOON) "am" else "pm"
                "ZZ" -> zoneText + two(abs(offset) % MINUTES_PER_HOUR)
                "Z" -> zoneText + ":" + two(abs(offset) % MINUTES_PER_HOUR)
                "X" -> (epochMillis / SECOND_MS.toLong()).toString()
                "x" -> epochMillis.toString()
                else -> token.substring(1, token.length - 1)
            }
        }
    }

    companion object {
        private val LIST_TYPES = setOf("multitext", "tags", "aliases")

        private val DATE =
            Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?(Z)?$""")

        private val FORMAT_TOKEN =
            Regex("""\[[^\]]*\]|YYYY|YY|Q|MMMM|MMM|MM|M|Do|DD|D|dddd|ddd|dd|d|HH|H|hh|h|mm|m|ss|s|SSS|A|a|ZZ|Z|X|x""")

        private val MONTHS =
            listOf(
                "January",
                "February",
                "March",
                "April",
                "May",
                "June",
                "July",
                "August",
                "September",
                "October",
                "November",
                "December",
            )
        private val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

        private const val HOUR_MS = 3_600_000.0
        private const val MINUTE_MS = 60_000.0
        private const val SECOND_MS = 1000.0
        private const val NANOS_PER_MS = 1_000_000
        private const val MAX_DIGITS = 100
        private const val TEN = 10.0
        private const val MAX_REPEAT = 10_000_000
        private const val WHOLE_LIMIT = 1e15
        private const val DAYS_IN_WEEK = 7
        private const val SECONDS_PER_MINUTE = 60
        private const val MINUTES_PER_HOUR = 60
        private const val CENTURY = 100
        private const val MONTHS_PER_QUARTER = 3
        private const val SHORT_NAME = 3
        private const val NOON = 12

        /** A column's heading when the base gives it no `displayName`. */
        fun label(id: String): String =
            when {
                id.startsWith("file.") -> "file " + id.substring(5)
                id.startsWith("formula.") -> id.substring(8)
                id.startsWith("note.") -> id.substring(5)
                else -> id
            }

        private fun two(value: Int): String = value.toString().padStart(2, '0')

        private fun ordinal(value: Int): String {
            val suffix =
                if (value % 100 in 11..13) {
                    "th"
                } else {
                    when (value % 10) {
                        1 -> "st"
                        2 -> "nd"
                        3 -> "rd"
                        else -> "th"
                    }
                }
            return "$value$suffix"
        }

        /** Swift's `rounded()`: half away from zero, where Kotlin's `rint` rounds half to even. */
        private fun roundHalfAway(value: Double): Double =
            if (value <
                0
            ) {
                -Math.round(-value).toDouble()
            } else {
                Math.round(value).toDouble()
            }

        /** moment's `fromNow`: "3 days ago", "in an hour". */
        fun relative(
            epochMillis: Long,
            now: Long,
        ): String {
            val seconds = (epochMillis - now) / SECOND_MS
            val text = humanize(abs(seconds))
            return if (seconds > 0) "in $text" else "$text ago"
        }

        /** moment's thresholds for a length of time in words. */
        fun humanize(seconds: Double): String {
            val s = abs(seconds)
            val minutes = s / 60
            val hours = minutes / 60
            val days = hours / 24

            fun count(
                value: Double,
                unit: String,
            ) = "${roundHalfAway(value).whole()?.toString() ?: "many"} $unit"
            return when {
                s < 45 -> "a few seconds"
                s < 90 -> "a minute"
                minutes < 45 -> count(minutes, "minutes")
                minutes < 90 -> "an hour"
                hours < 22 -> count(hours, "hours")
                hours < 36 -> "a day"
                days < 26 -> count(days, "days")
                days < 45 -> "a month"
                days < 320 -> count(days / 30.4375, "months")
                days < 548 -> "a year"
                else -> count(days / 365.25, "years")
            }
        }

        /**
         * Text order as Finder sorts names: case aside, and a run of digits by
         * its value, so "Item 9" comes before "Item 10".
         */
        fun naturalCompare(
            a: String,
            b: String,
        ): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val x = a[i]
                val y = b[j]
                if (x.isDigit() && y.isDigit()) {
                    val endI = (i until a.length).firstOrNull { !a[it].isDigit() } ?: a.length
                    val endJ = (j until b.length).firstOrNull { !b[it].isDigit() } ?: b.length
                    val p = a.substring(i, endI).trimStart('0')
                    val q = b.substring(j, endJ).trimStart('0')
                    val order = if (p.length != q.length) p.length.compareTo(q.length) else p.compareTo(q)
                    if (order != 0) return order.coerceIn(-1, 1)
                    i = endI
                    j = endJ
                } else {
                    val order = x.lowercaseChar().compareTo(y.lowercaseChar())
                    if (order != 0) return order.coerceIn(-1, 1)
                    i++
                    j++
                }
            }
            return (a.length - i).compareTo(b.length - j).coerceIn(-1, 1)
        }
    }
}
