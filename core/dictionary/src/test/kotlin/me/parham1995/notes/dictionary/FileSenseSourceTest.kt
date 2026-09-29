package me.parham1995.notes.dictionary

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class FileSenseSourceTest {
    @Test
    fun `a line is read from its offset to its newline, across reads and in UTF-8`() {
        val long = "é".repeat(700)
        val text = "first\n$long\nlast"
        val file = File.createTempFile("senses", null).apply { deleteOnExit() }
        file.writeText(text)

        FileSenseSource(file).use { source ->
            assertThat(source.lineAt(0)).isEqualTo("first")
            assertThat(source.lineAt(6)).isEqualTo(long)
            assertThat(source.lineAt(6L + long.toByteArray().size + 1)).isEqualTo("last")
        }
    }
}
