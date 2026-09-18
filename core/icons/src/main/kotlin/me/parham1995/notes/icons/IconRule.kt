package me.parham1995.notes.icons

/** The item a rule is being tested against. */
data class IconTarget(
    /** Vault-relative, with no leading slash. */
    val path: String,
    val isFolder: Boolean,
) {
    /** Last path segment, extension included. */
    val filename: String get() = path.substringAfterLast('/')

    /** Last path segment without its extension -- what Obsidian shows as the name. */
    val name: String get() = if (isFolder) filename else filename.substringBeforeLast('.', filename)

    val extension: String get() = if (isFolder) "" else filename.substringAfterLast('.', "")

    /**
     * The folder this item belongs to. A folder is its own tree, which is what
     * makes `^Companies/[^/]*$` select the company folders rather than their
     * contents.
     */
    val tree: String get() = if (isFolder) path else path.substringBeforeLast('/', "")
}

/** How a rule combines its conditions. */
enum class RuleMatch {
    ALL,
    ANY,
    NONE,
    ;

    companion object {
        fun parse(raw: String?): RuleMatch = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALL
    }
}

/**
 * One test in a rule.
 *
 * [source] and [operator] are kept as Iconic wrote them rather than being
 * mapped onto enums covering every case the plugin supports. Iconic has sources
 * this app cannot evaluate at all -- frontmatter properties, tags, file dates --
 * and inventing enum constants for them would only make an unevaluable
 * condition look evaluable. An unrecognised source or operator simply does not
 * match, which is the safe direction: an icon is missing rather than wrong.
 */
data class IconCondition(
    val source: String,
    val operator: String,
    val value: String,
) {
    private val negated: Boolean = operator.startsWith("!")
    private val verb: String = operator.removePrefix("!")

    // Compiled once per condition rather than per row. A rule is tested against
    // every visible item, so a regex recompiled on each test would show up.
    private val pattern: Regex? by lazy {
        if (verb != "matches") {
            null
        } else {
            runCatching { Regex(value) }.getOrNull()
        }
    }

    fun matches(target: IconTarget): Boolean {
        val subject =
            when (source) {
                "name" -> target.name
                "filename" -> target.filename
                "extension" -> target.extension
                "path" -> target.path
                "tree" -> target.tree
                else -> return false
            }
        val hit =
            when (verb) {
                "is" -> subject == value
                "contains" -> subject.contains(value)
                "startsWith" -> subject.startsWith(value)
                "endsWith" -> subject.endsWith(value)
                "matches" -> pattern?.containsMatchIn(subject) ?: return false
                else -> return false
            }
        return hit != negated
    }
}

/** An Iconic rule: an icon applied to everything its conditions select. */
data class IconRule(
    val id: String,
    val name: String,
    val match: RuleMatch,
    val conditions: List<IconCondition>,
    val icon: IconSpec?,
    val enabled: Boolean = true,
) {
    fun matches(target: IconTarget): Boolean {
        if (!enabled || conditions.isEmpty()) return false
        return when (match) {
            RuleMatch.ALL -> conditions.all { it.matches(target) }
            RuleMatch.ANY -> conditions.any { it.matches(target) }
            RuleMatch.NONE -> conditions.none { it.matches(target) }
        }
    }
}
