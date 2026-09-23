package me.parham1995.notes.markdown

/**
 * A Markdown link's destination, read the way Obsidian reads it.
 *
 * `[text](destination)` is a web address only when it says so with a scheme.
 * Anything else is inside the vault -- `Other%20Note.md`, `folder/Note`,
 * `#Heading`, `../uploads/scan.pdf` -- and was being handed to the system as a
 * URL, which opened nothing and said nothing.
 */
object MarkdownLinks {
    /** `https:`, `mailto:`, `obsidian:`, `tel:` -- or a protocol-relative `//host`. */
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:""")

    fun isExternal(destination: String): Boolean = SCHEME.containsMatchIn(destination) || destination.startsWith("//")

    /** A destination inside the vault, decoded: what it names, and at which heading. */
    data class Internal(
        val target: String,
        val heading: String?,
    )

    /**
     * Splits an internal destination into its target and heading, and undoes
     * the percent-encoding Obsidian writes for spaces and non-Latin names.
     *
     * An empty target is this note -- `[see below](#Details)`.
     */
    fun internal(destination: String): Internal {
        val hash = destination.indexOf('#')
        val rawTarget = if (hash >= 0) destination.substring(0, hash) else destination
        val rawHeading = if (hash >= 0) destination.substring(hash + 1) else null
        return Internal(
            target = decode(rawTarget).trim().removePrefix("/"),
            heading = rawHeading?.let { decode(it).trim() }?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Whether a target names a file rather than a note: it has an extension,
     * and it is not `.md`.
     */
    fun isFile(target: String): Boolean {
        val extension = target.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension != "md" && FILE_EXTENSION.matches(extension)
    }

    /**
     * [target] as a vault path, seen from the note at [source]: a `./` or
     * `../` path is relative to that note's folder, anything else is already
     * from the vault's root.
     */
    fun fromNote(
        target: String,
        source: String,
    ): String {
        if (!target.startsWith("./") && !target.startsWith("../")) return target
        val base =
            source
                .substringBeforeLast('/', "")
                .split('/')
                .filter { it.isNotEmpty() }
                .toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                ".", "" -> Unit
                ".." -> if (base.isNotEmpty()) base.removeAt(base.lastIndex)
                else -> base += segment
            }
        }
        return base.joinToString("/")
    }

    /**
     * Percent-decoding without `URLDecoder`, which also turns `+` into a
     * space -- and `C++ notes.md` is a file name, not a form submission.
     * Malformed escapes are left as they were written.
     */
    fun decode(value: String): String {
        if (!value.contains('%')) return value
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val byte =
                if (char == '%' && index + 2 <= value.lastIndex) {
                    value
                        .substring(index + 1, index + ESCAPE_LENGTH)
                        .takeIf { hex -> hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
                        ?.toInt(HEX)
                } else {
                    null
                }
            if (byte != null) {
                bytes.write(byte)
                index += ESCAPE_LENGTH
            } else {
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private const val HEX = 16
    private const val ESCAPE_LENGTH = 3
    private val FILE_EXTENSION = Regex("""[a-z0-9]{1,6}""")
}
