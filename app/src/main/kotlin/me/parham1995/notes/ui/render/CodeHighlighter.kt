package me.parham1995.notes.ui.render

import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.ui.theme.Naz

/**
 * Colours code with the same palette the vault is written in.
 *
 * Nearly a third of this vault's lines sit inside code fences, so unhighlighted
 * code is most of what a reader looks at. The theme maps onto naz's own
 * highlight groups -- String is chartreuse, Keyword is aqua, Comment is grey --
 * so a snippet reads the same on the phone as in the editor.
 */
object CodeHighlighter {
    private val cache = LruCache<String, AnnotatedString>(CACHE_ENTRIES)

    /** naz's groups, expressed as the shape the tokeniser wants. */
    private val nazTheme =
        SyntaxTheme(
            key = "naz",
            code = Naz.White.rgb(),
            keyword = Naz.Aqua.rgb(),
            string = Naz.Chartreuse.rgb(),
            literal = Naz.Purple.rgb(),
            comment = Naz.Grey.rgb(),
            metadata = Naz.Orange.rgb(),
            multilineComment = Naz.Grey.rgb(),
            punctuation = Naz.WhiteDarker.rgb(),
            mark = Naz.VividYellow.rgb(),
        )

    /**
     * Fence labels to the tokeniser's languages. Anything unmapped still gets
     * strings, numbers and comments from the default grammar, which is worth
     * more than nothing for the yaml, sql and hcl this vault is full of.
     */
    private val languages =
        mapOf(
            "bash" to SyntaxLanguage.SHELL,
            "sh" to SyntaxLanguage.SHELL,
            "zsh" to SyntaxLanguage.SHELL,
            "shell" to SyntaxLanguage.SHELL,
            "console" to SyntaxLanguage.SHELL,
            "python" to SyntaxLanguage.PYTHON,
            "py" to SyntaxLanguage.PYTHON,
            "go" to SyntaxLanguage.GO,
            "golang" to SyntaxLanguage.GO,
            "c" to SyntaxLanguage.C,
            "cpp" to SyntaxLanguage.CPP,
            "c++" to SyntaxLanguage.CPP,
            "csharp" to SyntaxLanguage.CSHARP,
            "cs" to SyntaxLanguage.CSHARP,
            "java" to SyntaxLanguage.JAVA,
            "kotlin" to SyntaxLanguage.KOTLIN,
            "kt" to SyntaxLanguage.KOTLIN,
            "rust" to SyntaxLanguage.RUST,
            "rs" to SyntaxLanguage.RUST,
            "typescript" to SyntaxLanguage.TYPESCRIPT,
            "ts" to SyntaxLanguage.TYPESCRIPT,
            "javascript" to SyntaxLanguage.JAVASCRIPT,
            "js" to SyntaxLanguage.JAVASCRIPT,
            "ruby" to SyntaxLanguage.RUBY,
            "rb" to SyntaxLanguage.RUBY,
            "php" to SyntaxLanguage.PHP,
            "swift" to SyntaxLanguage.SWIFT,
            "dart" to SyntaxLanguage.DART,
            "perl" to SyntaxLanguage.PERL,
        )

    /** True when the fence has a grammar rather than falling back to defaults. */
    fun isKnown(language: String?): Boolean = language?.lowercase() in languages

    suspend fun highlight(
        code: String,
        language: String?,
    ): AnnotatedString {
        val key = (language ?: "") + "|" + code.hashCode() + "|" + code.length
        cache.get(key)?.let { return it }

        // Long fences are not rare in a technical vault, and tokenising one on
        // the frame that scrolls to it is visible.
        return withContext(Dispatchers.Default) {
            runCatching {
                val highlights =
                    Highlights
                        .Builder()
                        .code(code)
                        .theme(nazTheme)
                        .language(languages[language?.lowercase()] ?: SyntaxLanguage.DEFAULT)
                        .build()
                        .getHighlights()

                AnnotatedString
                    .Builder(code)
                    .apply {
                        highlights.forEach { highlight ->
                            val start = highlight.location.start.coerceIn(0, code.length)
                            val end = highlight.location.end.coerceIn(start, code.length)
                            if (start == end) return@forEach
                            when (highlight) {
                                is ColorHighlight ->
                                    addStyle(SpanStyle(color = Color(highlight.rgb or OPAQUE)), start, end)

                                is BoldHighlight ->
                                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                            }
                        }
                    }.toAnnotatedString()
            }.getOrElse { AnnotatedString(code) }
                .also { cache.put(key, it) }
        }
    }

    // Color.value is a packed ULong, not ARGB -- toArgb() is the conversion.
    private fun Color.rgb(): Int = toArgb() and RGB_MASK

    private const val OPAQUE = 0xFF000000.toInt()
    private const val RGB_MASK = 0xFFFFFF
    private const val CACHE_ENTRIES = 64
}
