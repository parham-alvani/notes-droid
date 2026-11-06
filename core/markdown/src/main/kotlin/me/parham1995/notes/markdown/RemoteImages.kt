package me.parham1995.notes.markdown

import java.net.URI

/**
 * Pictures a note takes from the web, `![chart](https://example.com/c.png)`
 * or `<img src="https://…">`: showing one asks that site for it, and the site
 * learns the note was opened, from where and when. With the setting off they
 * are not asked for until tapped -- `remote-images.md` in notes-linux's
 * `docs/specs/`. A picture from the vault or a `data:` one asks no one.
 */
object RemoteImages {
    /** Whether showing [src] asks a site for it: `http://` or `https://`, in any case. */
    fun isRemote(src: String): Boolean =
        src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)

    /** The site [src] is asked of, for the placeholder to name; the address itself if it has none. */
    fun host(src: String): String =
        runCatching { URI(src.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() }?.lowercase()
            ?: src
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .ifEmpty { src }
}
