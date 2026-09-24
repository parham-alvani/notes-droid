package me.parham1995.notes.obsidian

import java.net.URLDecoder

/**
 * An `obsidian://` link, as another app or a note elsewhere hands it over.
 *
 * [vault] is the vault's name as Obsidian knows it -- the folder name on the
 * desktop -- and is null when the link does not say, which Obsidian reads as
 * "the vault already open".
 */
sealed interface ObsidianLink {
    val vault: String?

    /**
     * Open a note, or only the vault when [file] is null.
     *
     * [file] is written the way Obsidian accepts it: a vault-relative path or
     * a bare name, with or without `.md` -- the same thing a wikilink target
     * is, and resolved the same way.
     */
    data class Open(
        override val vault: String?,
        val file: String? = null,
        val heading: String? = null,
    ) : ObsidianLink

    data class Search(
        override val vault: String?,
        val query: String,
    ) : ObsidianLink
}

object ObsidianUri {
    private const val SCHEME = "obsidian://"

    /**
     * What [uri] asks for, or null when it is not a link this app can follow.
     *
     * Handles `obsidian://open?vault=&file=`, `obsidian://search?vault=&query=`
     * and the shorthand `obsidian://vault/<vault>/<file>`. `open?path=` names
     * an absolute path on the machine that wrote it, which means nothing on a
     * phone, so it opens the vault if the link names one and is otherwise not
     * followed. Pure on purpose: the intent filter matches the scheme, and
     * everything about what the link means is decided here, testable without
     * an Android URI.
     */
    fun parse(uri: String): ObsidianLink? {
        if (!uri.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = uri.substring(SCHEME.length)
        val action = rest.substringBefore('?').substringBefore('#').trimEnd('/')
        val query = parameters(rest.substringAfter('?', ""))
        val vault = query["vault"]?.takeIf { it.isNotBlank() }
        return when {
            action.equals("open", ignoreCase = true) -> {
                val file = query["file"]?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
                val (path, heading) = split(file)
                if (path == null && vault == null) null else ObsidianLink.Open(vault, path, heading)
            }
            action.equals("search", ignoreCase = true) ->
                ObsidianLink.Search(vault, query["query"].orEmpty())
            action.startsWith("vault/", ignoreCase = true) -> {
                val parts = action.substring("vault/".length).split('/', limit = 2).map(::decode)
                val name = parts.first().takeIf { it.isNotBlank() } ?: return null
                val (path, heading) = split(parts.getOrNull(1)?.trim('/')?.takeIf { it.isNotEmpty() })
                ObsidianLink.Open(name, path, heading)
            }
            else -> null
        }
    }

    /** `Note#Heading` into the note and the heading; a `#^block` keeps only the note. */
    private fun split(file: String?): Pair<String?, String?> {
        if (file == null) return null to null
        val hash = file.indexOf('#')
        if (hash < 0) return file to null
        val heading = file.substring(hash + 1).takeIf { it.isNotBlank() && !it.startsWith("^") }
        return file.substring(0, hash).trim('/').takeIf { it.isNotEmpty() } to heading
    }

    private fun parameters(query: String): Map<String, String> =
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = decode(pair.substringBefore('='))
                key to decode(pair.substringAfter('=', ""))
            }

    /**
     * Percent-decoding, and only that. `URLDecoder` also reads `+` as a
     * space, which is form encoding rather than URI encoding: Obsidian writes
     * a literal plus as `%2B`, so one arriving bare is a plus that was never
     * encoded -- a note called "C++" -- and has to stay one.
     */
    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(text)
}
