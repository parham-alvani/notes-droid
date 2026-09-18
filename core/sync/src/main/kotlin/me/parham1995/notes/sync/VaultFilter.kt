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
    /**
     * Files under a dot-directory that are content anyway. Matched exactly, so
     * this cannot widen into "sync `.obsidian`".
     */
    private val configPaths: Set<String> = DEFAULT_CONFIG_PATHS,
) {
    /** The kind of [path], or null when it is not vault content at all. */
    fun kindOf(path: String): BlobKind? {
        if (path.isEmpty()) return null

        if (path in configPaths) return BlobKind.CONFIG

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
            extension in ATTACHMENT_EXTENSIONS -> BlobKind.OTHER
            else -> null
        }
    }

    fun accepts(path: String): Boolean = kindOf(path) != null

    companion object {
        /**
         * Bumped whenever this class starts or stops accepting a path.
         *
         * A device only ever learns about files that *changed* since its last
         * sync, so widening the filter is invisible to it: the newly-eligible
         * file did not change, so no refresh will ever mention it. Recording
         * the version the manifest was built with lets a sync notice it is out
         * of date and plan from the full tree once, which costs one request and
         * picks up exactly what is missing.
         *
         * 2: the Iconic plugin's icon assignments.
         * 3: attachments -- PDFs, video, audio, archives, office documents.
         */
        const val VERSION = 3

        val DEFAULT_EXCLUDED_ROOTS = setOf("node_modules")

        /**
         * Where the Iconic plugin records which icon belongs to which note or
         * folder.
         *
         * This is the one file under `.obsidian` the app wants, and the reason
         * is that it is not editor configuration in the usual sense -- it is
         * part of how the vault reads, the same way a note's title is. Leaving
         * it out would mean either shipping a second copy of the assignments
         * with the app, which goes stale the moment an icon changes, or
         * dropping the feature. It is a few tens of kilobytes.
         */
        const val ICONIC_CONFIG = ".obsidian/plugins/iconic/data.json"

        val DEFAULT_CONFIG_PATHS = setOf(ICONIC_CONFIG)

        /**
         * Formats the reader draws itself. Everything else it can offer is in
         * [ATTACHMENT_EXTENSIONS] and gets handed to another app instead.
         */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "svg", "webp")

        /**
         * Files the vault holds that another app can open.
         *
         * Recorded in the manifest but, like images, not downloaded until
         * something asks for one -- so a path and a sha is all a default
         * install carries for a 40MB video.
         *
         * A list rather than "anything that is not markdown", because a vault
         * repository also contains the source of whatever tooling lives beside
         * the notes. This vault checks in two Obsidian plugins; syncing their
         * `.ts` and `.json` would be filling the manifest with things no reader
         * can do anything with.
         */
        val ATTACHMENT_EXTENSIONS =
            setOf(
                "pdf",
                "epub",
                "mp4",
                "mov",
                "mkv",
                "webm",
                "avi",
                "mp3",
                "m4a",
                "ogg",
                "opus",
                "wav",
                "flac",
                "zip",
                "tar",
                "gz",
                "7z",
                "doc",
                "docx",
                "odt",
                "rtf",
                "xls",
                "xlsx",
                "ods",
                "csv",
                "ppt",
                "pptx",
                "odp",
                "drawio",
                "txt",
            )
    }
}
