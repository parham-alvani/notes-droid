package me.parham1995.notes.obsidian

/**
 * A folder's logo: a `logo.svg` (or a picture) inside it is drawn as the
 * folder's icon -- a company's logo on its folder -- and is the folder's, not
 * a file in it. The same rule as Daftar on the Mac, so both show the same.
 */
object FolderLogos {
    /** The names a logo may have, best first: a drawing before a picture. */
    val NAMES = listOf("logo.svg", "logo.png", "logo.webp", "logo.jpg", "logo.jpeg")

    /** Whether [path] is a logo file, by its name alone. */
    fun isLogo(path: String): Boolean = path.substringAfterLast('/').lowercase() in NAMES

    /**
     * The logo of each folder among [paths], by folder. Case does not matter,
     * and a drawing wins over a picture in the same folder.
     */
    fun byFolder(paths: Collection<String>): Map<String, String> =
        paths
            .filter(::isLogo)
            .groupBy { it.substringBeforeLast('/', "") }
            .mapValues { (_, logos) -> logos.minBy { NAMES.indexOf(it.substringAfterLast('/').lowercase()) } }
}
