package me.parham1995.notes.sync

import java.util.Locale

/**
 * Decides which of a repository's files are part of the vault.
 *
 * A vault repository carries more than notes -- editor configuration, tooling,
 * CI, sometimes whole npm projects -- and none of it should reach the device.
 * The rule is deliberately simple so it stays predictable: dot-directories are
 * never vault content, and only markdown and images are.
 */
class VaultFilter(
    /**
     * Record images in the manifest. They are still not downloaded unless the
     * image policy asks for it -- knowing a path and its sha costs nothing and
     * is what makes fetch-on-open possible.
     */
    private val includeImages: Boolean = true,
    /**
     * Top-level directories to skip wholesale. `node_modules` is usually
     * gitignored anyway, but a vault that checks it in would otherwise sync
     * tens of thousands of files.
     */
    private val excludedRoots: Set<String> = DEFAULT_EXCLUDED_ROOTS,
) {
    /** The kind of [path], or null when it is not vault content at all. */
    fun kindOf(path: String): BlobKind? {
        if (path.isEmpty()) return null

        val segments = path.split('/')
        // Anything under a dot-directory is tooling, not notes: .obsidian,
        // .github, .husky, .claude.
        if (segments.any { it.startsWith(".") }) return null
        if (segments.first() in excludedRoots) return null

        val name = segments.last()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension == "md" -> BlobKind.MARKDOWN
            includeImages && extension in IMAGE_EXTENSIONS -> BlobKind.IMAGE
            else -> null
        }
    }

    fun accepts(path: String): Boolean = kindOf(path) != null

    companion object {
        val DEFAULT_EXCLUDED_ROOTS = setOf("node_modules")

        /**
         * Only formats that can actually be shown inline. Video and audio are
         * left out on purpose -- they are handed to another app rather than
         * rendered, so there is no reason to sync them.
         */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "svg", "webp")
    }
}
