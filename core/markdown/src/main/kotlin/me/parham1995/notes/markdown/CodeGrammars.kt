package me.parham1995.notes.markdown

/** What a run of characters is, so the renderer can colour it. */
enum class TokenKind {
    COMMENT,
    STRING,
    NUMBER,
    KEYWORD,

    /** The name half of a mapping: a YAML key, a TOML setting, an HCL argument. */
    KEY,
}

data class CodeToken(
    val start: Int,
    val end: Int,
    val kind: TokenKind,
)

/**
 * Colouring for the languages the syntax library has no grammar for.
 *
 * It has about twenty-five, and this vault writes in ninety-two. The gap is not
 * a long tail: `yaml` alone is 1,550 of its 12,393 fences, and with `sql`,
 * `powershell`, `zig`, `hcl`, `json` and `promql` it is around 3,500 -- a
 * quarter of all the code in the vault, sitting there in one colour.
 *
 * These are not real parsers and do not pretend to be. A fence in a note is
 * read, not compiled, and what makes it readable is that strings, comments,
 * numbers and keywords look different from one another. A scanner that gets
 * that right is worth far more than nothing and cannot fail in an interesting
 * way -- the worst case is a word coloured that should not have been.
 */
object CodeGrammars {
    /**
     * How one language is shaped. Everything here is about lexing, not syntax:
     * where comments start, what quotes a string, which words are reserved.
     */
    private data class Grammar(
        val lineComments: List<String> = listOf("#"),
        val blockComment: Pair<String, String>? = null,
        val quotes: String = "\"'",
        val keywords: Set<String> = emptySet(),
        /** Colour `name:` or `name =` at the head of a line as a key. */
        val keys: Boolean = false,
        /** Keywords are matched without regard to case, as SQL is written. */
        val ignoreCase: Boolean = false,
    )

    private val YAML = Grammar(keys = true, keywords = setOf("true", "false", "null", "yes", "no", "on", "off"))

    private val JSON =
        Grammar(
            lineComments = emptyList(),
            quotes = "\"",
            keywords = setOf("true", "false", "null"),
            keys = true,
        )

    private val SQL =
        Grammar(
            lineComments = listOf("--", "#"),
            blockComment = "/*" to "*/",
            ignoreCase = true,
            keywords =
                setOf(
                    "select",
                    "from",
                    "where",
                    "join",
                    "inner",
                    "left",
                    "right",
                    "outer",
                    "full",
                    "on",
                    "group",
                    "by",
                    "order",
                    "having",
                    "limit",
                    "offset",
                    "insert",
                    "into",
                    "values",
                    "update",
                    "set",
                    "delete",
                    "create",
                    "table",
                    "index",
                    "view",
                    "drop",
                    "alter",
                    "add",
                    "column",
                    "primary",
                    "key",
                    "foreign",
                    "references",
                    "unique",
                    "not",
                    "null",
                    "and",
                    "or",
                    "in",
                    "exists",
                    "between",
                    "like",
                    "as",
                    "distinct",
                    "union",
                    "all",
                    "case",
                    "when",
                    "then",
                    "else",
                    "end",
                    "with",
                    "returning",
                    "conflict",
                    "do",
                    "begin",
                    "commit",
                    "rollback",
                    "explain",
                    "analyze",
                    "asc",
                    "desc",
                    "count",
                    "sum",
                    "avg",
                    "min",
                    "max",
                    "cast",
                    "coalesce",
                ),
        )

    private val HCL =
        Grammar(
            lineComments = listOf("#", "//"),
            blockComment = "/*" to "*/",
            keys = true,
            keywords =
                setOf(
                    "resource",
                    "variable",
                    "output",
                    "module",
                    "provider",
                    "data",
                    "locals",
                    "terraform",
                    "backend",
                    "for_each",
                    "count",
                    "depends_on",
                    "true",
                    "false",
                    "null",
                    "if",
                    "for",
                    "in",
                ),
        )

    private val TOML = Grammar(keys = true, keywords = setOf("true", "false"))

    private val POWERSHELL =
        Grammar(
            blockComment = "<#" to "#>",
            keywords =
                setOf(
                    "function",
                    "param",
                    "begin",
                    "process",
                    "end",
                    "if",
                    "elseif",
                    "else",
                    "switch",
                    "foreach",
                    "for",
                    "while",
                    "do",
                    "until",
                    "break",
                    "continue",
                    "return",
                    "try",
                    "catch",
                    "finally",
                    "throw",
                    "filter",
                    "in",
                    "class",
                    "enum",
                    "using",
                    "true",
                    "false",
                    "null",
                ),
        )

    private val ZIG =
        Grammar(
            lineComments = listOf("//"),
            keywords =
                setOf(
                    "const",
                    "var",
                    "fn",
                    "pub",
                    "return",
                    "if",
                    "else",
                    "while",
                    "for",
                    "switch",
                    "struct",
                    "enum",
                    "union",
                    "error",
                    "try",
                    "catch",
                    "defer",
                    "errdefer",
                    "comptime",
                    "inline",
                    "test",
                    "usingnamespace",
                    "async",
                    "await",
                    "suspend",
                    "resume",
                    "unreachable",
                    "orelse",
                    "and",
                    "or",
                    "true",
                    "false",
                    "null",
                    "undefined",
                ),
        )

    private val PROMQL =
        Grammar(
            keywords =
                setOf(
                    "by",
                    "without",
                    "on",
                    "ignoring",
                    "group_left",
                    "group_right",
                    "offset",
                    "bool",
                    "and",
                    "or",
                    "unless",
                    "rate",
                    "irate",
                    "increase",
                    "sum",
                    "avg",
                    "min",
                    "max",
                    "count",
                    "topk",
                    "bottomk",
                    "quantile",
                    "histogram_quantile",
                    "delta",
                    "idelta",
                    "abs",
                    "ceil",
                    "floor",
                    "round",
                    "clamp_max",
                    "clamp_min",
                    "label_replace",
                ),
        )

    private val DOCKERFILE =
        Grammar(
            keywords =
                setOf(
                    "from",
                    "run",
                    "cmd",
                    "label",
                    "expose",
                    "env",
                    "add",
                    "copy",
                    "entrypoint",
                    "volume",
                    "user",
                    "workdir",
                    "arg",
                    "onbuild",
                    "stopsignal",
                    "healthcheck",
                    "shell",
                    "as",
                ),
            ignoreCase = true,
        )

    private val BY_NAME =
        mapOf(
            "yaml" to YAML,
            "yml" to YAML,
            "json" to JSON,
            "jsonc" to JSON,
            "json5" to JSON,
            "sql" to SQL,
            "postgresql" to SQL,
            "psql" to SQL,
            "mysql" to SQL,
            "hcl" to HCL,
            "terraform" to HCL,
            "tf" to HCL,
            "toml" to TOML,
            "ini" to TOML,
            "conf" to TOML,
            "properties" to TOML,
            "editorconfig" to TOML,
            "powershell" to POWERSHELL,
            "ps1" to POWERSHELL,
            "pwsh" to POWERSHELL,
            "zig" to ZIG,
            "promql" to PROMQL,
            "dockerfile" to DOCKERFILE,
            "docker" to DOCKERFILE,
        )

    /** True when this fence label has a scanner here. */
    fun handles(language: String?): Boolean = language?.lowercase() in BY_NAME

    /** Tokens for [code], or null when nothing here knows the language. */
    fun tokenize(
        code: String,
        language: String?,
    ): List<CodeToken>? {
        val grammar = BY_NAME[language?.lowercase()] ?: return null
        return scan(code, grammar)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun scan(
        code: String,
        grammar: Grammar,
    ): List<CodeToken> {
        val tokens = mutableListOf<CodeToken>()
        var at = 0
        // True until something other than whitespace is seen on this line, so a
        // key can be told from a word that merely contains a colon.
        var atLineStart = true

        while (at < code.length) {
            val char = code[at]

            if (char == '\n') {
                atLineStart = true
                at++
                continue
            }

            val block = grammar.blockComment
            if (block != null && code.startsWith(block.first, at)) {
                val closeAt = code.indexOf(block.second, at + block.first.length)
                val end = if (closeAt < 0) code.length else closeAt + block.second.length
                tokens += CodeToken(at, end, TokenKind.COMMENT)
                at = end
                atLineStart = false
                continue
            }

            val lineComment = grammar.lineComments.firstOrNull { code.startsWith(it, at) }
            if (lineComment != null && (at == 0 || code[at - 1] != '$')) {
                val end = code.indexOf('\n', at).let { if (it < 0) code.length else it }
                tokens += CodeToken(at, end, TokenKind.COMMENT)
                at = end
                continue
            }

            if (char in grammar.quotes) {
                val end = closingQuote(code, at, char)
                tokens += CodeToken(at, end, TokenKind.STRING)
                at = end
                atLineStart = false
                continue
            }

            if (char.isDigit() && (at == 0 || !code[at - 1].isWordPart())) {
                var end = at
                while (end < code.length && (code[end].isDigit() || code[end] == '.' || code[end] == '_')) end++
                tokens += CodeToken(at, end, TokenKind.NUMBER)
                at = end
                atLineStart = false
                continue
            }

            if (char.isWordStart()) {
                var end = at
                while (end < code.length && code[end].isWordPart()) end++
                val word = code.substring(at, end)

                val isKey = grammar.keys && atLineStart && followedByAssignment(code, end)
                val isKeyword =
                    grammar.keywords.any {
                        if (grammar.ignoreCase) it.equals(word, ignoreCase = true) else it == word
                    }

                when {
                    isKey -> tokens += CodeToken(at, end, TokenKind.KEY)
                    isKeyword -> tokens += CodeToken(at, end, TokenKind.KEYWORD)
                }
                at = end
                atLineStart = false
                continue
            }

            if (!char.isWhitespace()) atLineStart = false
            at++
        }
        return tokens
    }

    /** The index just past the closing quote, or the end of the input. */
    private fun closingQuote(
        code: String,
        open: Int,
        quote: Char,
    ): Int {
        var at = open + 1
        while (at < code.length) {
            when {
                code[at] == '\\' -> at += 2
                code[at] == quote -> return at + 1
                // An unterminated string is a fragment in a note, not a syntax
                // error to recover from. It ends at the line, not at the file.
                code[at] == '\n' -> return at
                else -> at++
            }
        }
        return code.length
    }

    /** `name:` or `name =`, allowing the spaces people actually write. */
    private fun followedByAssignment(
        code: String,
        from: Int,
    ): Boolean {
        var at = from
        while (at < code.length && (code[at] == ' ' || code[at] == '\t')) at++
        if (at >= code.length) return false
        return code[at] == ':' || code[at] == '='
    }

    private fun Char.isWordStart(): Boolean = isLetter() || this == '_' || this == '$'

    private fun Char.isWordPart(): Boolean = isLetterOrDigit() || this == '_' || this == '-' || this == '$'
}
