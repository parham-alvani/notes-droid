package me.parham1995.notes.markdown

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A time of an OKF concept: an instant, or text that is not one. */
sealed interface OkfTime {
    data class At(
        val instant: Instant,
    ) : OkfTime

    /** A date-time without an offset, or anything else that is no time. Shown as malformed. */
    data class Malformed(
        val raw: String,
    ) : OkfTime

    val instantOrNull: Instant? get() = (this as? At)?.instant
}

/** `{by, at}`: an actor and when. */
data class OkfEvent(
    val by: String,
    val at: OkfTime,
) {
    /** Only `human:` is a person; `team:`, `process:` and `<producer>/<version>` are not. */
    val byPerson: Boolean get() = Okf.isPerson(by)
}

/** How far a concept's text is confirmed (OKF §5.3). */
enum class OkfTier {
    UNVERIFIED,
    MACHINE_CONFIRMED,
    HUMAN_REVIEWED,
}

sealed interface OkfStatus {
    data object Draft : OkfStatus

    data object Stable : OkfStatus

    data object Deprecated : OkfStatus

    /** Any other value, kept as written. Not stable. */
    data class Other(
        val raw: String,
    ) : OkfStatus

    val isStable: Boolean get() = this == Stable
}

/**
 * A note as the Open Knowledge Format reads it: one whose front matter has a
 * `type`. `open-knowledge-format.md` in notes-linux's `docs/specs/` has the
 * rules, which Daftar for Linux and the Mac follow too; change one here and
 * theirs have to follow. Section numbers written §N are OKF's own.
 */
data class OkfConcept(
    /** The kind, any text: no list of kinds is known. */
    val type: String,
    val title: String? = null,
    val description: String? = null,
    val resource: String? = null,
    val tags: List<String> = emptyList(),
    val status: OkfStatus = OkfStatus.Stable,
    /** A path, read as a Markdown link's target is. Used only with [OkfStatus.Deprecated]. */
    val supersededBy: String? = null,
    val staleAfter: OkfTime? = null,
    /** The actor that wrote the text, and when it last changed. */
    val generated: OkfEvent? = null,
    val verified: List<OkfEvent> = emptyList(),
) {
    val deprecated: Boolean get() = status == OkfStatus.Deprecated

    /**
     * The events that still say something of the text as it is: those at or
     * after `generated.at`, or all of them when nothing says when the text
     * was generated.
     */
    val currentEvents: List<OkfEvent>
        get() {
            val written =
                when (val at = generated?.at) {
                    null -> return verified
                    // Nothing can be shown to come after a time that cannot be read.
                    is OkfTime.Malformed -> return emptyList()
                    is OkfTime.At -> at.instant
                }
            return verified.filter { event -> event.at.instantOrNull?.let { !it.isBefore(written) } == true }
        }

    val tier: OkfTier
        get() {
            val current = currentEvents
            return when {
                current.isEmpty() -> OkfTier.UNVERIFIED
                current.any { it.byPerson } -> OkfTier.HUMAN_REVIEWED
                else -> OkfTier.MACHINE_CONFIRMED
            }
        }

    /** The last current event, for the tier's tooltip: who, and which day. */
    val lastVerifier: OkfEvent? get() = currentEvents.maxByOrNull { it.at.instantOrNull ?: Instant.MIN }

    /**
     * Stale when [now] is at or after `stale_after`. A malformed one is
     * stale, never fresh; none is fresh.
     */
    fun isStale(now: Instant): Boolean =
        when (val after = staleAfter) {
            null -> false
            is OkfTime.Malformed -> true
            is OkfTime.At -> !now.isBefore(after.instant)
        }
}

/** Why a concept could not be marked. */
class OkfFailure(
    message: String,
) : Exception(message)

object Okf {
    private val NEVER = setOf("index.md", "log.md")
    private val DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?\s?([Zz]|[+-]\d{2}:?\d{2})""")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    /**
     * The concept the note at [path] is, or null: a note with no front
     * matter, no `type`, or an empty one, and `index.md` and `log.md`
     * whatever they say (§3.1).
     */
    fun conceptOf(
        note: String,
        path: String,
    ): OkfConcept? {
        if (path.substringAfterLast('/') in NEVER) return null
        return concept(frontMatter(note) ?: return null)
    }

    /**
     * Reads [frontMatter], the YAML between the fences, with OKF's consumer
     * rules (§11.2): null, an empty text and an empty list are an absent
     * key; one item where a list is asked is a list of one. Nothing is ever
     * refused: a key that breaks a rule is read as far as it can be.
     */
    fun concept(frontMatter: String): OkfConcept? {
        val keys = runCatching { Yaml.parse(frontMatter) }.getOrNull() as? YamlNode.Mapping ?: return null
        val type = text(keys["type"]) ?: return null
        return OkfConcept(
            type = type,
            title = text(keys["title"]),
            description = text(keys["description"]),
            resource = text(keys["resource"]),
            // A scalar is one tag, never split at commas.
            tags = keys["tags"]?.items.orEmpty().mapNotNull(::text),
            status =
                when (val status = text(keys["status"])) {
                    null, "stable" -> OkfStatus.Stable
                    "draft" -> OkfStatus.Draft
                    "deprecated" -> OkfStatus.Deprecated
                    else -> OkfStatus.Other(status)
                },
            supersededBy = text(keys["superseded_by"]),
            staleAfter = text(keys["stale_after"])?.let(::time),
            generated = keys["generated"]?.let(::event),
            // One mapping is a list of one; an event without both keys is left out.
            verified = events(keys["verified"]).mapNotNull(::event),
        )
    }

    /** Whether [note], an `index.md`, is the root of a bundle: its front matter has `okf_version`. */
    fun isBundleRoot(note: String): Boolean {
        val keys = frontMatter(note)?.let { runCatching { Yaml.parse(it) }.getOrNull() } ?: return false
        return keys["okf_version"].let { it != null && it != YamlNode.Null }
    }

    /**
     * A time: a date-time with an offset, or a date alone, which is the
     * start of that day in UTC. A date-time without an offset, or anything
     * else, is malformed.
     */
    fun time(raw: String): OkfTime {
        val text = raw.trim()
        if (DATE.matches(text)) {
            return runCatching { OkfTime.At(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()) }
                .getOrDefault(OkfTime.Malformed(raw))
        }
        if (!DATE_TIME.matches(text)) return OkfTime.Malformed(raw)
        // As ISO writes it: a `T`, seconds, and a colon in the offset.
        var iso = text.replaceFirst(Regex("""^(\d{4}-\d{2}-\d{2})[Tt ]"""), "$1T").replace(" ", "").replace('z', 'Z')
        iso = iso.replaceFirst(Regex("""T(\d{2}:\d{2})(Z|[+-])"""), "T$1:00$2")
        iso = iso.replaceFirst(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2")
        return runCatching { OkfTime.At(OffsetDateTime.parse(iso).toInstant()) }.getOrDefault(OkfTime.Malformed(raw))
    }

    /** Whether [actor] is a person: `human:<id>`, and nothing else. */
    fun isPerson(actor: String): Boolean = actor.startsWith("human:") && actor.length > "human:".length

    /** Now as an OKF time is written: UTC, to the second. */
    fun stamp(now: Instant): String = DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(ChronoUnit.SECONDS))

    /**
     * [note] marked as verified by [actor] at [at]: `{ by, at }` added to
     * `verified`, in place of an event of that actor already there, and
     * every other line of the note byte for byte. Refused, with the reason,
     * on a note that is not a concept.
     */
    fun markVerified(
        note: String,
        path: String,
        actor: String,
        at: String,
    ): String {
        if (conceptOf(note, path) ==
            null
        ) {
            throw OkfFailure("Only a concept, a note with a type, is marked as verified.")
        }
        val lines = note.split('\n')
        val close = (1 until lines.size).first { lines[it].bare() == "---" || lines[it].bare() == "..." }
        val eol = if (lines[0].endsWith("\r")) "\r" else ""
        val start = (1 until close).firstOrNull { isKey(lines[it], "verified") }
        val end =
            start?.let { from ->
                var to = (from + 1 until close).firstOrNull { isTopLevel(lines[it]) } ?: close
                // The blank lines after the key stay where they are.
                while (to > from + 1 && lines[to - 1].bare().isEmpty()) to--
                to
            }

        val kept =
            if (start == null || end == null) {
                emptyList()
            } else {
                val block = lines.subList(start, end).joinToString("\n") { it.removeSuffix("\r") }
                events(runCatching { Yaml.parse(block) }.getOrNull()?.get("verified"))
                    .filter { text(it["by"]) != actor }
            }
        val written = mutableListOf("verified:")
        // A block list, one event a line, those already there in their order.
        kept.forEach { event ->
            written +=
                "  - { " +
                event.entries.joinToString(
                    ", ",
                ) { (key, value) -> "$key: " + quoted(text(value).orEmpty()) } +
                " }"
        }
        written += "  - { by: " + quoted(actor) + ", at: " + quoted(at) + " }"

        val out = lines.subList(0, start ?: close) + written.map { it + eol } + lines.subList(end ?: close, lines.size)
        return out.joinToString("\n")
    }

    /** The YAML between a note's fences, or null when it has no front matter. */
    private fun frontMatter(note: String): String? {
        val lines = note.split('\n')
        if (lines.firstOrNull()?.bare() != "---") return null
        val close = (1 until lines.size).firstOrNull { lines[it].bare() == "---" || lines[it].bare() == "..." }
        return lines.subList(1, close ?: return null).joinToString("\n") { it.removeSuffix("\r") }
    }

    private fun String.bare(): String = removeSuffix("\r").trim(' ', '\t')

    private fun isKey(
        line: String,
        key: String,
    ): Boolean = Regex("""^["']?${Regex.escape(key)}["']?\s*:(\s.*)?$""").matches(line.removeSuffix("\r"))

    /** A line that starts another key: not blank, not indented, not a list's item and not a comment. */
    private fun isTopLevel(line: String): Boolean {
        val first = line.removeSuffix("\r").firstOrNull() ?: return false
        return first != ' ' && first != '\t' && first != '-' && first != '#'
    }

    private fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** A scalar's text, or null for nothing: null, empty, or not a scalar. */
    private fun text(node: YamlNode?): String? = node?.string?.trim()?.takeIf { it.isNotEmpty() }

    /** The mappings of a `verified`: a list's, or the one mapping. A flow mapping, `{ by: …, at: … }`, is one too. */
    private fun events(node: YamlNode?): List<YamlNode.Mapping> =
        when (val read = node?.let(::mapping)) {
            null, YamlNode.Null -> emptyList()
            is YamlNode.Mapping -> listOf(read)
            is YamlNode.Sequence -> read.values.mapNotNull { mapping(it) as? YamlNode.Mapping }
            else -> emptyList()
        }

    private fun event(node: YamlNode): OkfEvent? {
        val keys = mapping(node) as? YamlNode.Mapping ?: return null
        return OkfEvent(by = text(keys["by"]) ?: return null, at = time(text(keys["at"]) ?: return null))
    }

    /** [node] with a flow mapping written on one line read as the mapping it is. */
    private fun mapping(node: YamlNode): YamlNode {
        val text = (node as? YamlNode.Scalar)?.text?.trim() ?: return node
        if (!text.startsWith("{") || !text.endsWith("}")) return node
        val inner = text.substring(1, text.length - 1).trim()
        if (inner.isEmpty()) return YamlNode.Mapping(emptyList())
        return YamlNode.Mapping(
            Yaml.splitFlow(inner).mapNotNull { pair ->
                Yaml.splitKey(pair)?.let { (key, value) -> key to Yaml.inline(value) }
            },
        )
    }
}
