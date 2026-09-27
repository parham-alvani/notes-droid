package me.parham1995.notes.ui.render

import android.content.Context
import android.util.LruCache
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.markdown.Contacts
import me.parham1995.notes.markdown.Contacts.withoutOverlaps

/**
 * Addresses, phone numbers and email addresses in a note's plain text, made
 * into things a tap opens: the maps app, the dialer, a new email.
 *
 * Two sources. [Contacts] finds the numbers and addresses whose shape says
 * what they are, the same on every phone and testable on the JVM. The
 * platform's text classifier finds the rest -- a street address above all,
 * which no pattern can -- where the phone has one that knows the language.
 * Neither is required: a phone without a classifier still gets the numbers.
 */
object ContactLinks {
    // Keyed by the text itself: the same paragraph is asked about on every
    // scroll past it, and classifying is a model run, not a regex.
    private val cache = LruCache<String, List<Contacts.Found>>(CACHE_SIZE)

    suspend fun find(
        context: Context,
        text: String,
    ): List<Contacts.Found> {
        // Nothing reachable is written without a digit or an @.
        if (text.none { it == '@' || it.isDigit() }) return emptyList()
        cache.get(text)?.let { return it }
        val found =
            withContext(Dispatchers.Default) {
                (Contacts.find(text) + classified(context, text)).withoutOverlaps()
            }
        cache.put(text, found)
        return found
    }

    private fun classified(
        context: Context,
        text: String,
    ): List<Contacts.Found> =
        runCatching {
            val classifier =
                context.getSystemService(TextClassificationManager::class.java)?.textClassifier
                    ?: return emptyList()
            if (classifier == TextClassifier.NO_OP || text.length > classifier.maxGenerateLinksTextLength) {
                return emptyList()
            }
            val config =
                TextClassifier.EntityConfig
                    .Builder()
                    .setIncludedTypes(WANTED)
                    .includeTypesFromTextClassifier(false)
                    .build()
            val request =
                TextLinks.Request
                    .Builder(text)
                    .setEntityConfig(config)
                    .build()
            classifier.generateLinks(request).links.mapNotNull { link ->
                val entity = (0 until link.entityCount).map { link.getEntity(it) }.firstOrNull { it in WANTED }
                val written = text.substring(link.start, link.end)
                when (entity) {
                    TextClassifier.TYPE_ADDRESS ->
                        Contacts.Found(link.start, link.end, Contacts.Kind.ADDRESS, Contacts.geo(written))
                    TextClassifier.TYPE_PHONE ->
                        Contacts.Found(
                            link.start,
                            link.end,
                            Contacts.Kind.PHONE,
                            "tel:" + written.filter { it.isDigit() || it == '+' },
                        )
                    TextClassifier.TYPE_EMAIL ->
                        Contacts.Found(link.start, link.end, Contacts.Kind.EMAIL, "mailto:$written")
                    else -> null
                }
            }
        }.getOrDefault(emptyList())

    private val WANTED = listOf(TextClassifier.TYPE_ADDRESS, TextClassifier.TYPE_PHONE, TextClassifier.TYPE_EMAIL)
    private const val CACHE_SIZE = 256
}

/**
 * [found] laid over the text as links, wherever the text is not a link
 * already -- a wikilink that happens to contain a number keeps going to its
 * note.
 */
internal fun AnnotatedString.withContacts(
    found: List<Contacts.Found>,
    colors: ColorScheme,
    open: (String) -> Unit,
): AnnotatedString {
    val free = found.filter { it.end <= length && getLinkAnnotations(it.start, it.end).isEmpty() }
    if (free.isEmpty()) return this
    val style = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))
    return AnnotatedString
        .Builder(this)
        .apply {
            free.forEach { contact ->
                addLink(
                    LinkAnnotation.Clickable(CONTACT_PREFIX + contact.uri, style) { open(contact.uri) },
                    contact.start,
                    contact.end,
                )
            }
        }.toAnnotatedString()
}

private const val CONTACT_PREFIX = "contact:"
