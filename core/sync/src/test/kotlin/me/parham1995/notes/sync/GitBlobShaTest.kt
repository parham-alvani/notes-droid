package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitBlobShaTest {
    @Test
    fun `matches what git hash-object gives`() {
        // $ printf 'hello\n' | git hash-object --stdin
        assertThat(gitBlobSha("hello\n".toByteArray()))
            .isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a")
    }

    @Test
    fun `an empty file has git's well-known empty blob sha`() {
        assertThat(gitBlobSha(ByteArray(0))).isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")
    }
}
