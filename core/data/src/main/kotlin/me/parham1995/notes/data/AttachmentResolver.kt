package me.parham1995.notes.data

/**
 * Where an embedded file actually lives, the way Obsidian finds it.
 *
 * Obsidian's default is to write the shortest path that is unambiguous, which
 * for most attachments is the bare file name: `![[photo.png]]` for a file that
 * sits in `Assets/2024/photo.png`. Looking that up at the vault root found
 * nothing, and the image rendered as broken although the file was on the
 * device.
 *
 * Tried in Obsidian's order: the path as written, from the vault root; then
 * relative to the note; then any one file whose path ends with what was
 * written. Only this vault's files are candidates -- the caller passes them --
 * because an embed resolves inside the vault that wrote it and nowhere else.
 */
internal object AttachmentResolver {
    fun resolve(
        target: String,
        source: String,
        known: Collection<String>,
    ): String {
        if (SCHEME.containsMatchIn(target)) return target
        val decoded = percentDecode(target)
        return find(target, source, known)
            ?: decoded.takeIf { it != target }?.let { find(it, source, known) }
            ?: fallback(decoded, source)
    }

    private fun find(
        target: String,
        source: String,
        known: Collection<String>,
    ): String? {
        val written = target.removePrefix("/")
        val folder = source.substringBeforeLast('/', "")
        val fromRoot = normalise("", written)
        val fromNote = normalise(folder, written)
        val explicitlyRelative = written.startsWith("./") || written.startsWith("../")

        val ordered = if (explicitlyRelative) listOf(fromNote, fromRoot) else listOf(fromRoot, fromNote)
        ordered.firstOrNull { it != null && it in known }?.let { return it }

        // Shortest path: the written text is the tail of exactly one file's
        // path. When several share it, the one nearest the note is what
        // Obsidian would have written the short form for.
        val tail = fromRoot ?: return null
        val matches = known.filter { it == tail || it.endsWith("/$tail") }
        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> matches.minWith(compareBy({ -sharedDepth(it, folder) }, { it.count { c -> c == '/' } }, { it }))
        }
    }

    /** What the reader was given before any of this: the path, made vault-relative. */
    private fun fallback(
        target: String,
        source: String,
    ): String {
        val written = target.removePrefix("/")
        return if (written.contains("..")) {
            normalise(source.substringBeforeLast('/', ""), written) ?: written
        } else {
            written
        }
    }

    /** Joins and collapses `.` and `..`; null when it climbs out of the vault. */
    private fun normalise(
        base: String,
        relative: String,
    ): String? {
        val parts = base.split('/').filter { it.isNotEmpty() }.toMutableList()
        relative.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return parts.joinToString("/").ifEmpty { null }
    }

    private fun sharedDepth(
        path: String,
        folder: String,
    ): Int {
        val a = path.substringBeforeLast('/', "").split('/')
        val b = folder.split('/')
        return a.zip(b).takeWhile { (x, y) -> x == y && x.isNotEmpty() }.size
    }

    /**
     * `%20` and friends, as a markdown image destination writes a space.
     *
     * By hand rather than `URLDecoder`, which is for form bodies and turns
     * every `+` in a file name into a space. Anything malformed is returned
     * untouched.
     */
    internal fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            if (text[index] == '%') {
                val hex = text.substring(index + 1, minOf(index + 3, text.length))
                if (hex.length != 2 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return text
                bytes.write(hex.toInt(HEX))
                index += 3
            } else {
                // A run at a time, so a character outside the BMP is encoded
                // whole rather than as two halves.
                val end = text.indexOf('%', index).takeIf { it >= 0 } ?: text.length
                bytes.write(text.substring(index, end).toByteArray(Charsets.UTF_8))
                index = end
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private const val HEX = 16
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
}
