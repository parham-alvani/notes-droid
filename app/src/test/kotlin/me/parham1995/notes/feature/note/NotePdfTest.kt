package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.CalloutKind
import org.junit.Test

/** What a shared PDF is called, which is what the other app shows. */
class NotePdfTest {
    @Test
    fun `a title becomes a name any app can hold`() {
        assertThat(NotePdf.fileName("Addresses")).isEqualTo("Addresses.pdf")
        assertThat(NotePdf.fileName("Plan: A/B?")).isEqualTo("Plan  A B.pdf")
        assertThat(NotePdf.fileName("  ")).isEqualTo("Note.pdf")
        assertThat(NotePdf.fileName("یادداشت")).isEqualTo("یادداشت.pdf")
    }

    @Test
    fun `a callout's bar is in its kind's colour, as the Linux PDF has them`() {
        assertThat(NotePdf.calloutColor(CalloutKind.TIP)).isEqualTo(NotePdf.calloutColor(CalloutKind.SUCCESS))
        assertThat(NotePdf.calloutColor(CalloutKind.WARNING)).isEqualTo(NotePdf.calloutColor(CalloutKind.QUESTION))
        assertThat(NotePdf.calloutColor(CalloutKind.DANGER)).isEqualTo(NotePdf.calloutColor(CalloutKind.BUG))
        assertThat(NotePdf.calloutColor(CalloutKind.NOTE)).isEqualTo(NotePdf.calloutColor(CalloutKind.INFO))
        val kinds =
            listOf(
                CalloutKind.NOTE,
                CalloutKind.TIP,
                CalloutKind.WARNING,
                CalloutKind.DANGER,
                CalloutKind.EXAMPLE,
                CalloutKind.QUOTE,
            )
        assertThat(kinds.map(NotePdf::calloutColor).toSet()).hasSize(kinds.size)
    }
}
