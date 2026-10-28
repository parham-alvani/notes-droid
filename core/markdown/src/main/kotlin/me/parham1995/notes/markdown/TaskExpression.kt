package me.parham1995.notes.markdown

import java.util.Locale

/**
 * The JavaScript a ` ```tasks ` query's `filter by function` and
 * `group by function` lines are written in -- the small part of it such lines
 * use in practice, read without a JavaScript engine.
 *
 * Strings, arrays of them, `true`/`false`/`null`, the `task.*` properties the
 * index can answer, `===`/`!==`/`==`/`!=`, `&&`/`||`/`!`, `? :`, brackets,
 * and the string and array methods `includes`, `startsWith`, `endsWith`,
 * `toLowerCase` and `toUpperCase`. Anything else -- another property, another
 * method, arithmetic -- fails to parse, so the line is reported rather than
 * guessed at.
 */
class TaskExpression private constructor(
    private val root: Node,
) {
    /** The value for [task]: a String, a Boolean, a List, or null. */
    fun evaluate(task: QueryTask): Any? = root.eval(task)

    /** JavaScript's truthiness, which is what a filter asks of the value. */
    fun test(task: QueryTask): Boolean = truthy(evaluate(task))

    companion object {
        /** Reads [source], or null when it uses anything outside the subset. */
        fun parse(source: String): TaskExpression? =
            runCatching {
                val parser = Parser(tokens(source))
                val node = parser.expression()
                parser.end()
                TaskExpression(node)
            }.getOrNull()

        /**
         * What `task.<path>` reads, or null when the index does not keep it.
         * The names are the Tasks plugin's own.
         */
        private fun property(path: String): ((QueryTask) -> Any?)? =
            when (path) {
                "status.type" -> { t -> t.status.type.name }
                "status.name" -> { t -> t.status.name }
                "status.symbol" -> { t -> t.status.symbol.toString() }
                "reason" -> { t -> t.reason }
                "description" -> { t -> t.text }
                "heading" -> { t -> t.section.ifBlank { null } }
                "hasHeading" -> { t -> t.section.isNotBlank() }
                "isDone" -> { t -> t.status.type == StatusType.DONE }
                "isRecurring" -> { t -> t.recurring != null }
                "file.path" -> { t -> t.path }
                "file.filename" -> { t -> t.path.substringAfterLast('/') }
                "file.filenameWithoutExtension" -> { t -> t.path.substringAfterLast('/').removeSuffix(".md") }
                "file.folder" -> { t -> t.path.folder() }
                else -> null
            }

        internal fun truthy(value: Any?): Boolean =
            when (value) {
                null -> false
                is Boolean -> value
                is String -> value.isNotEmpty()
                else -> true
            }

        private fun tokens(source: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    c.isWhitespace() -> i++
                    c == '"' || c == '\'' -> {
                        val end = source.indexOf(c, i + 1)
                        require(end > i)
                        // Kept with its opening quote, so a string token cannot
                        // be mistaken for a name or an operator.
                        out += "\"" + source.substring(i + 1, end)
                        i = end + 1
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                        out += source.substring(start, i)
                    }
                    else -> {
                        val op = OPERATORS.firstOrNull { source.startsWith(it, i) }
                        requireNotNull(op)
                        out += op
                        i += op.length
                    }
                }
            }
            return out
        }

        // Longest first, so `===` is not read as `==` and then `=`.
        private val OPERATORS =
            listOf("===", "!==", "==", "!=", "&&", "||", "!", "?", ":", "(", ")", "[", "]", ",", ".")
    }

    private fun interface Node {
        fun eval(task: QueryTask): Any?
    }

    /** Recursive descent, lowest precedence first: `? :`, `||`, `&&`, equality, `!`. */
    private class Parser(
        private val tokens: List<String>,
    ) {
        private var at = 0

        private fun peek(): String? = tokens.getOrNull(at)

        private fun take(expected: String) {
            require(peek() == expected)
            at++
        }

        fun end() = require(at == tokens.size)

        fun expression(): Node {
            val condition = or()
            if (peek() != "?") return condition
            take("?")
            val yes = expression()
            take(":")
            val no = expression()
            return Node { t -> if (truthy(condition.eval(t))) yes.eval(t) else no.eval(t) }
        }

        private fun or(): Node {
            var left = and()
            while (peek() == "||") {
                take("||")
                val l = left
                val r = and()
                left = Node { t -> l.eval(t).let { if (truthy(it)) it else r.eval(t) } }
            }
            return left
        }

        private fun and(): Node {
            var left = equality()
            while (peek() == "&&") {
                take("&&")
                val l = left
                val r = equality()
                left = Node { t -> l.eval(t).let { if (!truthy(it)) it else r.eval(t) } }
            }
            return left
        }

        private fun equality(): Node {
            var left = unary()
            while (peek() in EQUALITY) {
                val negate = peek()!!.startsWith("!")
                at++
                val l = left
                val r = unary()
                left = Node { t -> (l.eval(t) == r.eval(t)) != negate }
            }
            return left
        }

        private fun unary(): Node {
            if (peek() == "!") {
                take("!")
                val inner = unary()
                return Node { t -> !truthy(inner.eval(t)) }
            }
            return postfix(primary())
        }

        private fun primary(): Node {
            val token = requireNotNull(peek())
            at++
            return when {
                token.startsWith("\"") -> token.substring(1).let { s -> Node { s } }
                token == "true" -> Node { true }
                token == "false" -> Node { false }
                token == "null" || token == "undefined" -> Node { null }
                token == "(" -> expression().also { take(")") }
                token == "[" -> array()
                token == "task" -> taskProperty()
                else -> throw IllegalArgumentException(token)
            }
        }

        private fun array(): Node {
            val items = mutableListOf<Node>()
            if (peek() != "]") {
                items += expression()
                while (peek() == ",") {
                    take(",")
                    items += expression()
                }
            }
            take("]")
            return Node { t -> items.map { it.eval(t) } }
        }

        /** `task.a.b`, as long as the dotted names run -- but not into a method. */
        private fun taskProperty(): Node {
            val names = mutableListOf<String>()
            while (peek() == ".") {
                val next = tokens.getOrNull(at + 1) ?: break
                val candidate = (names + next).joinToString(".")
                if (property(candidate) == null && candidate !in PREFIXES) break
                take(".")
                names += next
                at++
            }
            val read = requireNotNull(property(names.joinToString(".")))
            return Node { t -> read(t) }
        }

        /** `.includes(x)` and the rest, applied to whatever came before. */
        private fun postfix(start: Node): Node {
            var node = start
            while (peek() == ".") {
                take(".")
                val method = requireNotNull(peek()).also { at++ }
                take("(")
                val args = mutableListOf<Node>()
                if (peek() != ")") {
                    args += expression()
                    while (peek() == ",") {
                        take(",")
                        args += expression()
                    }
                }
                take(")")
                node = call(node, method, args)
            }
            return node
        }

        private fun call(
            target: Node,
            method: String,
            args: List<Node>,
        ): Node =
            when (method) {
                "includes" -> {
                    require(args.size == 1)
                    Node { t ->
                        when (val on = target.eval(t)) {
                            is List<*> -> args[0].eval(t) in on
                            is String -> (args[0].eval(t) as? String)?.let { on.contains(it) } ?: false
                            else -> false
                        }
                    }
                }
                "startsWith", "endsWith" -> {
                    require(args.size == 1)
                    Node { t ->
                        val on = target.eval(t) as? String
                        val arg = args[0].eval(t) as? String
                        if (on == null ||
                            arg == null
                        ) {
                            false
                        } else if (method == "startsWith") {
                            on.startsWith(arg)
                        } else {
                            on.endsWith(arg)
                        }
                    }
                }
                "toLowerCase" -> Node { t -> (target.eval(t) as? String)?.lowercase(Locale.ROOT) }
                "toUpperCase" -> Node { t -> (target.eval(t) as? String)?.uppercase(Locale.ROOT) }
                else -> throw IllegalArgumentException(method)
            }

        private companion object {
            val EQUALITY = setOf("===", "!==", "==", "!=")

            /** Dotted names that lead to a property without being one. */
            val PREFIXES = setOf("status", "file")
        }
    }
}

/** A note's folder as the Tasks plugin writes it: with its slash, or `/` at the root. */
internal fun String.folder(): String = substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }
