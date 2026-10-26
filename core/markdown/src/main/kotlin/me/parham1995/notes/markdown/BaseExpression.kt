package me.parham1995.notes.markdown

/** A value in an Obsidian Bases expression. */
sealed interface BaseValue {
    data object Null : BaseValue

    data class Bool(
        val value: Boolean,
    ) : BaseValue

    data class Number(
        val value: Double,
    ) : BaseValue

    data class Text(
        val value: String,
    ) : BaseValue

    /** [time] is false for a date without a time of day, such as `2026-10-25`. */
    data class Date(
        val epochMillis: Long,
        val time: Boolean,
    ) : BaseValue

    data class Duration(
        val span: BaseSpan,
    ) : BaseValue

    data class ListValue(
        val items: List<BaseValue>,
    ) : BaseValue

    /** A link as written, and the vault path it goes to when it goes anywhere. */
    data class Link(
        val target: String,
        val path: String?,
        val display: String?,
    ) : BaseValue

    data class File(
        val path: String,
    ) : BaseValue

    data class Object(
        val entries: List<Pair<String, BaseValue>>,
    ) : BaseValue

    data class Pattern(
        val regex: Regex,
        val global: Boolean,
    ) : BaseValue
}

/** A length of time: whole months, which have no fixed length, and milliseconds. */
data class BaseSpan(
    val months: Int = 0,
    val milliseconds: Double = 0.0,
) {
    operator fun plus(other: BaseSpan) = BaseSpan(months + other.months, milliseconds + other.milliseconds)

    operator fun unaryMinus() = BaseSpan(-months, -milliseconds)

    /** For arithmetic and comparison, a month counts as 30 days. */
    val approximateMilliseconds: Double get() = milliseconds + months * MONTH_DAYS * DAY_MS

    companion object {
        private const val MONTH_DAYS = 30.0
        internal const val DAY_MS = 86_400_000.0
        private const val WEEK_MS = 604_800_000.0
        private const val HOUR_MS = 3_600_000.0
        private const val MINUTE_MS = 60_000.0
        private const val SECOND_MS = 1000.0
        private const val YEAR_MONTHS = 12

        /** Each unit's whole months and milliseconds. */
        private val UNITS: Map<String, Pair<Int, Double>> =
            buildMap {
                listOf("y", "year", "years").forEach { put(it, YEAR_MONTHS to 0.0) }
                listOf("M", "month", "months").forEach { put(it, 1 to 0.0) }
                listOf("w", "week", "weeks").forEach { put(it, 0 to WEEK_MS) }
                listOf("d", "day", "days").forEach { put(it, 0 to DAY_MS) }
                listOf("h", "hour", "hours").forEach { put(it, 0 to HOUR_MS) }
                listOf("m", "minute", "minutes").forEach { put(it, 0 to MINUTE_MS) }
                listOf("s", "second", "seconds").forEach { put(it, 0 to SECOND_MS) }
                listOf("ms", "millisecond", "milliseconds").forEach { put(it, 0 to 1.0) }
            }

        private val PART = Regex("""^\s*([+-]?\d+(?:\.\d+)?)\s*([A-Za-z]+)\s*,?""")

        /**
         * `"30 days"`, `"1d"`, `"1M 4h"`, `"-2 weeks"`: units y, M, w, d, h, m,
         * s, ms, or their names.
         */
        fun parse(text: String): BaseSpan? {
            var months = 0
            var ms = 0.0
            var found = false
            var rest = text.trim()
            while (rest.isNotEmpty()) {
                val match = PART.find(rest) ?: return null
                val amount = match.groupValues[1].toDoubleOrNull() ?: return null
                val name = match.groupValues[2]
                val unit = UNITS[name] ?: (if (name.length > 1) UNITS[name.lowercase()] else null) ?: return null
                if (unit.first != 0) {
                    val whole = amount.whole() ?: return null
                    if (amount != Math.rint(amount)) return null
                    val product = whole.toLong() * unit.first
                    val sum = months.toLong() + product
                    if (product !in Int.MIN_VALUE..Int.MAX_VALUE || sum !in Int.MIN_VALUE..Int.MAX_VALUE) return null
                    months = sum.toInt()
                } else {
                    ms += amount * unit.second
                }
                found = true
                rest = rest.substring(match.range.last + 1)
            }
            return if (found) BaseSpan(months, ms) else null
        }
    }
}

/** The whole number toward zero, or null when there is none: not a number, infinite, or past an `Int`. */
internal fun Double.whole(): Int? {
    if (isNaN() || isInfinite()) return null
    val truncated = if (this < 0) Math.ceil(this) else Math.floor(this)
    return if (truncated < Int.MIN_VALUE || truncated > Int.MAX_VALUE) null else truncated.toInt()
}

/** An expression as it is parsed, before it meets a file. */
sealed interface BaseExpr {
    data class Value(
        val value: BaseValue,
    ) : BaseExpr

    data class ListOf(
        val items: List<BaseExpr>,
    ) : BaseExpr

    data class Name(
        val name: String,
    ) : BaseExpr

    data class Member(
        val receiver: BaseExpr,
        val name: String,
    ) : BaseExpr

    data class Index(
        val receiver: BaseExpr,
        val key: BaseExpr,
    ) : BaseExpr

    /** A global function: `if(…)`, `now()`. */
    data class Call(
        val name: String,
        val arguments: List<BaseExpr>,
    ) : BaseExpr

    data class Method(
        val receiver: BaseExpr,
        val name: String,
        val arguments: List<BaseExpr>,
    ) : BaseExpr

    data class Unary(
        val symbol: String,
        val operand: BaseExpr,
    ) : BaseExpr

    data class Binary(
        val symbol: String,
        val left: BaseExpr,
        val right: BaseExpr,
    ) : BaseExpr
}

class BaseParseException(
    message: String,
) : Exception(message)

/**
 * The expression language of a `.base` file, as Obsidian documents it: the
 * operators `|| && == != < > <= >= + - * / % !`, method calls, `[index]`,
 * strings, numbers, lists and regular expressions.
 *
 * A port of the Mac app's `BaseParser`, so a base reads the same on both.
 */
object BaseParser {
    internal sealed interface Token {
        data class Num(
            val value: Double,
        ) : Token

        data class Str(
            val value: String,
        ) : Token

        data class Rx(
            val pattern: String,
            val flags: String,
        ) : Token

        data class Word(
            val name: String,
        ) : Token

        data class Symbol(
            val symbol: String,
        ) : Token
    }

    fun parse(source: String): BaseExpr {
        val parser = Parser(tokenize(source))
        val expression = parser.or()
        if (parser.at != parser.tokens.size) {
            throw BaseParseException("Unexpected ${parser.describe(parser.tokens[parser.at])}")
        }
        return expression
    }

    private val TWO_CHARACTER = setOf("==", "!=", "<=", ">=", "&&", "||")
    private const val ONE_CHARACTER = "()[],.!+-*/%<>"

    internal fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var at = 0

        fun endsValue(): Boolean =
            when (val last = tokens.lastOrNull()) {
                is Token.Num, is Token.Str, is Token.Rx, is Token.Word -> true
                is Token.Symbol -> last.symbol == ")" || last.symbol == "]"
                null -> false
            }

        fun asciiDigit(ch: Char) = ch in '0'..'9'

        while (at < source.length) {
            val ch = source[at]
            when {
                ch.isWhitespace() -> at++

                asciiDigit(ch) -> {
                    var end = at
                    while (end < source.length && asciiDigit(source[end])) end++
                    if (end + 1 < source.length && source[end] == '.' && asciiDigit(source[end + 1])) {
                        end++
                        while (end < source.length && asciiDigit(source[end])) end++
                    }
                    tokens += Token.Num(source.substring(at, end).toDouble())
                    at = end
                }

                ch == '"' || ch == '\'' -> {
                    val text = StringBuilder()
                    var end = at + 1
                    while (end < source.length && source[end] != ch) {
                        if (source[end] == '\\' && end + 1 < source.length) {
                            end++
                            text.append(
                                when (source[end]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    else -> source[end]
                                },
                            )
                        } else {
                            text.append(source[end])
                        }
                        end++
                    }
                    if (end >= source.length) throw BaseParseException("A string has no closing $ch")
                    tokens += Token.Str(text.toString())
                    at = end + 1
                }

                ch == '/' && !endsValue() -> {
                    val pattern = StringBuilder()
                    var end = at + 1
                    while (end < source.length && source[end] != '/') {
                        if (source[end] == '\\' && end + 1 < source.length) {
                            pattern.append(source[end])
                            end++
                        }
                        pattern.append(source[end])
                        end++
                    }
                    if (end >= source.length) throw BaseParseException("A regular expression has no closing /")
                    end++
                    val flags = StringBuilder()
                    while (end < source.length && source[end].isLetter()) {
                        flags.append(source[end])
                        end++
                    }
                    tokens += Token.Rx(pattern.toString(), flags.toString())
                    at = end
                }

                ch.isLetter() || ch == '_' || ch == '$' -> {
                    var end = at
                    while (end < source.length && (source[end].isLetterOrDigit() || source[end] in "_$")) end++
                    tokens += Token.Word(source.substring(at, end))
                    at = end
                }

                else -> {
                    val two = if (at + 1 < source.length) source.substring(at, at + 2) else ""
                    if (two in TWO_CHARACTER) {
                        tokens += Token.Symbol(two)
                        at += 2
                    } else if (ch in ONE_CHARACTER) {
                        tokens += Token.Symbol(ch.toString())
                        at++
                    } else {
                        throw BaseParseException("Unexpected “$ch”")
                    }
                }
            }
        }
        return tokens
    }

    private class Parser(
        val tokens: List<Token>,
    ) {
        var at = 0

        fun describe(token: Token): String =
            when (token) {
                is Token.Num -> token.value.toString()
                is Token.Str -> "\"${token.value}\""
                is Token.Rx -> "/${token.pattern}/"
                is Token.Word -> token.name
                is Token.Symbol -> "“${token.symbol}”"
            }

        fun take(symbol: String): Boolean {
            if (at < tokens.size && tokens[at] == Token.Symbol(symbol)) {
                at++
                return true
            }
            return false
        }

        fun expect(symbol: String) {
            if (!take(symbol)) {
                throw BaseParseException(
                    if (at <
                        tokens.size
                    ) {
                        "Expected “$symbol”, found ${describe(tokens[at])}"
                    } else {
                        "Expected “$symbol” at the end"
                    },
                )
            }
        }

        private fun binary(
            symbols: List<String>,
            next: () -> BaseExpr,
        ): BaseExpr {
            var left = next()
            while (true) {
                val symbol = symbols.firstOrNull { take(it) } ?: return left
                left = BaseExpr.Binary(symbol, left, next())
            }
        }

        fun or(): BaseExpr = binary(listOf("||")) { and() }

        private fun and(): BaseExpr = binary(listOf("&&")) { equality() }

        private fun equality(): BaseExpr = binary(listOf("==", "!=")) { comparison() }

        private fun comparison(): BaseExpr = binary(listOf("<=", ">=", "<", ">")) { additive() }

        private fun additive(): BaseExpr = binary(listOf("+", "-")) { multiplicative() }

        private fun multiplicative(): BaseExpr = binary(listOf("*", "/", "%")) { unary() }

        private fun unary(): BaseExpr {
            if (take("!")) return BaseExpr.Unary("!", unary())
            if (take("-")) return BaseExpr.Unary("-", unary())
            return postfix()
        }

        private fun arguments(): List<BaseExpr> {
            val items = mutableListOf<BaseExpr>()
            if (take(")")) return items
            do {
                items += or()
            } while (take(","))
            expect(")")
            return items
        }

        private fun postfix(): BaseExpr {
            var expression = primary()
            while (true) {
                expression =
                    when {
                        take(".") -> {
                            val name =
                                (tokens.getOrNull(at) as? Token.Word)?.name
                                    ?: throw BaseParseException("Expected a name after “.”")
                            at++
                            if (take(
                                    "(",
                                )
                            ) {
                                BaseExpr.Method(expression, name, arguments())
                            } else {
                                BaseExpr.Member(expression, name)
                            }
                        }
                        take("[") -> {
                            val key = or()
                            expect("]")
                            BaseExpr.Index(expression, key)
                        }
                        take("(") -> {
                            val name =
                                (expression as? BaseExpr.Name)?.name
                                    ?: throw BaseParseException("Only a function can be called")
                            BaseExpr.Call(name, arguments())
                        }
                        else -> return expression
                    }
            }
        }

        private fun primary(): BaseExpr {
            val token = tokens.getOrNull(at) ?: throw BaseParseException("The expression stops too early")
            at++
            return when (token) {
                is Token.Num -> BaseExpr.Value(BaseValue.Number(token.value))
                is Token.Str -> BaseExpr.Value(BaseValue.Text(token.value))
                is Token.Rx -> BaseExpr.Value(pattern(token))
                is Token.Word ->
                    when (token.name) {
                        "true" -> BaseExpr.Value(BaseValue.Bool(true))
                        "false" -> BaseExpr.Value(BaseValue.Bool(false))
                        "null" -> BaseExpr.Value(BaseValue.Null)
                        else -> BaseExpr.Name(token.name)
                    }
                is Token.Symbol ->
                    when (token.symbol) {
                        "(" -> or().also { expect(")") }
                        "[" -> {
                            val items = mutableListOf<BaseExpr>()
                            if (!take("]")) {
                                do {
                                    items += or()
                                } while (take(","))
                                expect("]")
                            }
                            BaseExpr.ListOf(items)
                        }
                        else -> throw BaseParseException("Unexpected ${describe(token)}")
                    }
            }
        }

        private fun pattern(token: Token.Rx): BaseValue {
            val options =
                buildSet {
                    if ('i' in token.flags) add(RegexOption.IGNORE_CASE)
                    if ('m' in token.flags) add(RegexOption.MULTILINE)
                    if ('s' in token.flags) add(RegexOption.DOT_MATCHES_ALL)
                }
            val regex =
                runCatching { Regex(token.pattern, options) }.getOrNull()
                    ?: throw BaseParseException("Not a regular expression: /${token.pattern}/")
            return BaseValue.Pattern(regex, global = 'g' in token.flags)
        }
    }
}
