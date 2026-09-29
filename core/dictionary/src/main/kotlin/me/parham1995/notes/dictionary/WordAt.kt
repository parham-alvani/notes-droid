package me.parham1995.notes.dictionary

/**
 * The word in [text] that the character at [offset] belongs to, or null when
 * that character is not part of one -- a space, a bullet, a digit on its own.
 *
 * A word is letters, with an apostrophe or a hyphen allowed between two of
 * them: "don't" and "well-known" are one word each, the quote around 'word'
 * is not part of it. A zero-width non-joiner counts as a letter, because
 * Persian writes one inside a word.
 */
fun wordAt(
    text: String,
    offset: Int,
): String? {
    if (offset !in text.indices || !isLetter(text[offset])) return null
    var start = offset
    while (start > 0 && (isLetter(text[start - 1]) || joins(text, start - 1))) start -= 1
    var end = offset + 1
    while (end < text.length && (isLetter(text[end]) || joins(text, end))) end += 1
    return text.substring(start, end)
}

private fun isLetter(char: Char): Boolean = char.isLetter() || char == ZWNJ

/** An apostrophe or hyphen with a letter on both sides of it. */
private fun joins(
    text: String,
    at: Int,
): Boolean =
    text[at] in JOINERS &&
        at > 0 &&
        at < text.lastIndex &&
        isLetter(text[at - 1]) &&
        isLetter(text[at + 1])

private const val ZWNJ = '‌'
private const val JOINERS = "'’-"
