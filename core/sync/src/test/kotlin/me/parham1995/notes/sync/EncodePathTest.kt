package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EncodePathTest {
    @Test
    fun `spaces become percent twenty, not a plus`() {
        assertThat(encodePath("Tasks/Code Chorus.md")).isEqualTo("Tasks/Code%20Chorus.md")
    }

    @Test
    fun `separators survive and non-latin script is encoded`() {
        assertThat(encodePath("a/b/c.md")).isEqualTo("a/b/c.md")
        assertThat(encodePath("سلام.md")).isEqualTo("%D8%B3%D9%84%D8%A7%D9%85.md")
    }
}
