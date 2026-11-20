package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

/**
 * The Open Knowledge Format's concepts: the tests `open-knowledge-format.md`
 * in notes-linux's `docs/specs/` asks of every app, by the names it gives
 * them. The sixth, the links, is in [OkfLinksTest].
 */
class OkfTest {
    private fun concept(frontMatter: String): OkfConcept? =
        Okf.conceptOf("---\n$frontMatter\n---\n\nText.\n", "Docs/x.md")

    private fun instant(text: String): Instant = Instant.parse(text)

    @Test
    fun aNoteWithATypeIsAConcept() {
        assertThat(concept("type: Metric")?.type).isEqualTo("Metric")

        assertThat(Okf.conceptOf("Text with no front matter.\n", "Docs/x.md")).isNull()
        assertThat(concept("title: A note")).isNull()
        assertThat(concept("type:")).isNull()
        assertThat(concept("type: \"\"")).isNull()
        assertThat(concept("type: null")).isNull()

        val note = "---\ntype: Metric\n---\n"
        assertThat(Okf.conceptOf(note, "Docs/index.md")).isNull()
        assertThat(Okf.conceptOf(note, "log.md")).isNull()
        assertThat(Okf.conceptOf(note, "Docs/index-note.md")).isNotNull()
    }

    @Test
    fun theTrustTierComesFromCurrentEvents() {
        val human = "verified: { by: \"human:parham\", at: \"2026-06-25T09:00:00Z\" }"
        val generated = "generated: { by: claude-code/2.1, at: \"2026-06-26T00:00:00Z\" }"

        assertThat(concept("type: Metric")!!.tier).isEqualTo(OkfTier.UNVERIFIED)
        // A bare mapping is a list of one.
        assertThat(concept("type: Metric\n$human")!!.tier).isEqualTo(OkfTier.HUMAN_REVIEWED)
        // The text changed after the review: the event is history, not a review of this text.
        val rewritten = concept("type: Metric\n$human\n$generated")!!
        assertThat(rewritten.tier).isEqualTo(OkfTier.UNVERIFIED)
        assertThat(rewritten.verified).hasSize(1)
        assertThat(rewritten.generated?.by).isEqualTo("claude-code/2.1")

        val nightly =
            """
            type: Metric
            $generated
            verified:
              - by: "human:parham"
                at: "2026-06-25T09:00:00Z"
              - by: process:nightly
                at: 2026-06-27T03:00:00Z
            """.trimIndent()
        assertThat(concept(nightly)!!.tier).isEqualTo(OkfTier.MACHINE_CONFIRMED)
        assertThat(concept(nightly)!!.lastVerifier?.by).isEqualTo("process:nightly")

        assertThat(concept("type: Metric\nverified: { by: \"team:x\", at: \"2026-06-25T09:00:00Z\" }")!!.tier)
            .isEqualTo(OkfTier.MACHINE_CONFIRMED)
        // An event with no `at` is left out.
        val noTime = concept("type: Metric\nverified:\n  - by: \"human:parham\"\n")!!
        assertThat(noTime.verified).isEmpty()
        assertThat(noTime.tier).isEqualTo(OkfTier.UNVERIFIED)
        // An event at the very second of `generated.at` is current.
        val same = "verified: { by: \"human:parham\", at: \"2026-06-26T00:00:00Z\" }"
        assertThat(concept("type: Metric\n$generated\n$same")!!.tier).isEqualTo(OkfTier.HUMAN_REVIEWED)
    }

    @Test
    fun aTimeHasAnOffsetOrIsADay() {
        val two = instant("2026-06-30T14:00:00Z")
        assertThat(Okf.time("2026-06-30T14:00:00Z")).isEqualTo(OkfTime.At(two))
        assertThat(Okf.time("2026-06-30T16:00:00+02:00")).isEqualTo(OkfTime.At(two))
        // Quoted or as YAML's own, read from a note.
        assertThat(concept("type: T\nstale_after: \"2026-06-30T14:00:00Z\"")!!.staleAfter).isEqualTo(OkfTime.At(two))
        assertThat(concept("type: T\nstale_after: 2026-06-30T14:00:00Z")!!.staleAfter).isEqualTo(OkfTime.At(two))
        assertThat(concept("type: T\nstale_after: 2026-06-30 16:00:00 +02:00")!!.staleAfter).isEqualTo(OkfTime.At(two))

        val day = OkfTime.At(instant("2026-06-30T00:00:00Z"))
        assertThat(concept("type: T\nstale_after: 2026-06-30")!!.staleAfter).isEqualTo(day)
        assertThat(concept("type: T\nstale_after: \"2026-06-30\"")!!.staleAfter).isEqualTo(day)

        listOf("2026-06-30T14:00:00", "30/06/2026", "soon", "2026-13-45").forEach {
            assertThat(Okf.time(it)).isEqualTo(OkfTime.Malformed(it))
        }
    }

    @Test
    fun aConceptIsStaleAtItsInstant() {
        val now = instant("2026-09-23T00:00:00Z")

        assertThat(concept("type: T\nstale_after: \"2026-09-23T00:00:00Z\"")!!.isStale(now)).isTrue()
        assertThat(concept("type: T\nstale_after: \"2026-09-23T00:00:01Z\"")!!.isStale(now)).isFalse()
        assertThat(concept("type: T")!!.isStale(now)).isFalse()
        assertThat(concept("type: T\nstale_after: null")!!.isStale(now)).isFalse()
        assertThat(concept("type: T\nstale_after: \"\"")!!.isStale(now)).isFalse()
        assertThat(concept("type: T\nstale_after:")!!.isStale(now)).isFalse()

        val soon = concept("type: T\nstale_after: \"soon\"")!!
        assertThat(soon.isStale(now)).isTrue()
        assertThat(soon.staleAfter).isEqualTo(OkfTime.Malformed("soon"))
    }

    @Test
    fun anUnknownStatusIsNotStable() {
        assertThat(concept("type: T")!!.status).isEqualTo(OkfStatus.Stable)
        assertThat(concept("type: T\nstatus: draft")!!.status).isEqualTo(OkfStatus.Draft)
        assertThat(concept("type: T\nstatus: stable")!!.status).isEqualTo(OkfStatus.Stable)
        assertThat(concept("type: T\nstatus: deprecated")!!.status).isEqualTo(OkfStatus.Deprecated)
        assertThat(concept("type: T\nstatus: deprecated")!!.deprecated).isTrue()

        val archived = concept("type: T\nstatus: archived")!!
        assertThat(archived.status).isEqualTo(OkfStatus.Other("archived"))
        assertThat(archived.status.isStable).isFalse()

        // A scalar is one tag, never split at commas; a list is its items.
        assertThat(concept("type: T\ntags: a, b")!!.tags).containsExactly("a, b")
        assertThat(concept("type: T\ntags: [a, b]")!!.tags).containsExactly("a", "b").inOrder()
        assertThat(concept("type: T\ntags: []")!!.tags).isEmpty()
    }

    @Test
    fun markAsVerifiedWritesOneEvent() {
        val note =
            listOf(
                "---",
                "type: Metric",
                "title: \"Revenue\"   # kept as written",
                "verified: { by: \"process:nightly\", at: \"2026-06-25T03:00:00Z\" }",
                "tags:",
                "  - money",
                "",
                "stale_after: 2026-12-31",
                "---",
                "",
                "The text, with a `---` of its own below.",
                "",
                "---",
                "",
            ).joinToString("\n")

        val marked = Okf.markVerified(note, "Docs/revenue.md", "human:parham", "2026-07-01T10:00:00Z")

        assertThat(marked.lines())
            .containsExactly(
                "---",
                "type: Metric",
                "title: \"Revenue\"   # kept as written",
                "verified:",
                "  - by: \"process:nightly\"",
                "    at: \"2026-06-25T03:00:00Z\"",
                "  - by: \"human:parham\"",
                "    at: \"2026-07-01T10:00:00Z\"",
                "tags:",
                "  - money",
                "",
                "stale_after: 2026-12-31",
                "---",
                "",
                "The text, with a `---` of its own below.",
                "",
                "---",
                "",
            ).inOrder()
        val concept = Okf.conceptOf(marked, "Docs/revenue.md")!!
        assertThat(concept.verified.map { it.by }).containsExactly("process:nightly", "human:parham").inOrder()
        assertThat(concept.tier).isEqualTo(OkfTier.HUMAN_REVIEWED)

        // Marking again replaces the actor's event; it is not added twice.
        val again = Okf.markVerified(marked, "Docs/revenue.md", "human:parham", "2026-08-01T10:00:00Z")
        val events = Okf.conceptOf(again, "Docs/revenue.md")!!.verified
        assertThat(events.map { it.by }).containsExactly("process:nightly", "human:parham").inOrder()
        assertThat(events.last().at).isEqualTo(OkfTime.At(instant("2026-08-01T10:00:00Z")))
        assertThat(again.lines().size).isEqualTo(marked.lines().size)

        // With no `verified` yet, the key is added last, and Windows line endings stay.
        val fresh =
            Okf.markVerified(
                "---\r\ntype: Metric\r\n---\r\nText.\r\n",
                "x.md",
                "human:parham",
                "2026-07-01T10:00:00Z",
            )
        assertThat(fresh).isEqualTo(
            "---\r\ntype: Metric\r\nverified:\r\n  - by: \"human:parham\"\r\n    at: \"2026-07-01T10:00:00Z\"\r\n---\r\nText.\r\n",
        )

        // On a note with no `type` it is refused.
        assertThrows(OkfFailure::class.java) {
            Okf.markVerified("---\ntitle: A note\n---\nText.\n", "x.md", "human:parham", "2026-07-01T10:00:00Z")
        }
        assertThrows(OkfFailure::class.java) {
            Okf.markVerified("Text.\n", "x.md", "human:parham", "2026-07-01T10:00:00Z")
        }
        assertThat(Okf.stamp(instant("2026-07-01T10:00:00.750Z"))).isEqualTo("2026-07-01T10:00:00Z")
    }
}
