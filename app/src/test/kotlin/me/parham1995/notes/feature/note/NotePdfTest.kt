package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
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
}
