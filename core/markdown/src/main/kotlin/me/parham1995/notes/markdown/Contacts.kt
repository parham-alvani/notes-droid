package me.parham1995.notes.markdown

/**
 * Phone numbers and email addresses written as plain text, so that a note
 * holding one can dial it or write to it with a tap.
 *
 * Deliberately narrow. A number in a note is far more often a date, a price, an
 * amount of something or an id than a phone number, and a false positive turns
 * a figure into a link that dials it. So a phone number is only something that
 * says so by its shape: an international `+` prefix, or a national number
 * written with its leading `0` (how numbers in Iran are written). Anything else
 * is left to the platform's text classifier, which knows the locale.
 */
object Contacts {
    enum class Kind { PHONE, EMAIL, ADDRESS }

    data class Found(
        val start: Int,
        val end: Int,
        val kind: Kind,
        /** What to open: `tel:`, `mailto:` or `geo:`. */
        val uri: String,
    )

    private const val DIGIT = "[0-9۰-۹٠-٩]"

    // A digit run with the usual separators inside it. The separators are
    // only ever single, so two numbers a space apart in a table row are not
    // read as one, and it has to end on a digit.
    private val PHONE = Regex("(?<![\\w+])(\\+|[0\u06F0\u0660])$DIGIT(?:[ .\\-]?\\(?$DIGIT\\)?){6,16}(?!\\w)")

    private val EMAIL =
        Regex("(?<![\\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![\\w-])")

    private val ISO_DATE = Regex("^$DIGIT{4}-$DIGIT{2}-$DIGIT{2}")

    fun find(text: String): List<Found> {
        if (text.none { it == '@' || it.isDigit() }) return emptyList()
        val emails =
            EMAIL.findAll(text).map { Found(it.range.first, it.range.last + 1, Kind.EMAIL, "mailto:${it.value}") }
        val phones =
            PHONE.findAll(text).mapNotNull { match ->
                val digits =
                    match.value
                        .filter { it.isDigit() }
                        .map { asciiDigit(it) }
                        .joinToString("")
                val international = match.value.startsWith("+")
                when {
                    ISO_DATE.containsMatchIn(match.value) -> null
                    // E.164 allows fifteen; a national number with its 0 is ten or eleven.
                    international && digits.length !in 8..15 -> null
                    !international && digits.length !in 10..11 -> null
                    else ->
                        Found(
                            match.range.first,
                            match.range.last + 1,
                            Kind.PHONE,
                            "tel:" + (if (international) "+" else "") + digits,
                        )
                }
            }
        return (emails + phones).sortedBy { it.start }.toList().withoutOverlaps()
    }

    /** A `geo:` link that asks the maps app to look [address] up. */
    fun geo(address: String): String = "geo:0,0?q=" + java.net.URLEncoder.encode(address.trim(), "UTF-8")

    /** Keeps the first of any two that share characters. */
    fun List<Found>.withoutOverlaps(): List<Found> {
        val kept = mutableListOf<Found>()
        sortedBy { it.start }.forEach { found ->
            if (kept.none { it.start < found.end && found.start < it.end }) {
                kept +=
                    found
            }
        }
        return kept
    }

    private fun asciiDigit(c: Char): Char =
        when (c) {
            in '۰'..'۹' -> '0' + (c - '۰')
            in '٠'..'٩' -> '0' + (c - '٠')
            else -> c
        }
}
