package me.parham1995.notes.markdown

/**
 * Per-block text direction, from the first strong character.
 *
 * Direction has to be detected rather than declared: only a handful of notes in
 * a mixed vault set `direction: rtl` in front matter, while many more simply
 * contain Persian. Detecting per block also handles the common real shape --
 * a Persian paragraph followed by a Latin code block in the same note.
 */
object TextDirection {
    fun of(text: String): MdDirection {
        for (ch in text) {
            when {
                isRtl(ch) -> return MdDirection.RTL
                isStrongLtr(ch) -> return MdDirection.LTR
            }
        }
        return MdDirection.LTR
    }

    /** True when any part of [text] is right-to-left, for the note-level flag. */
    fun containsRtl(text: String): Boolean = text.any { isRtl(it) }

    private fun isRtl(ch: Char): Boolean {
        val code = ch.code
        return code in 0x0590..0x05FF ||
            // Hebrew
            code in 0x0600..0x06FF ||
            // Arabic, and Persian with it
            code in 0x0700..0x074F ||
            // Syriac
            code in 0x0750..0x077F ||
            // Arabic supplement
            code in 0x08A0..0x08FF ||
            // Arabic extended-A
            code in 0xFB1D..0xFDFF ||
            // presentation forms
            code in 0xFE70..0xFEFF
    }

    private fun isStrongLtr(ch: Char): Boolean = ch.isLetter() && !isRtl(ch)
}
