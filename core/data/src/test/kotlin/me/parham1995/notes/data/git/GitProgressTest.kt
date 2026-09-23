package me.parham1995.notes.data.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import org.junit.Test

/** JGit asks the monitor whether to stop; it has to be able to say yes. */
class GitProgressTest {
    @Test
    fun `a cancelled sync tells JGit to stop`() {
        val progress = GitProgress()
        val job = Job()
        progress.bindTo(job)
        assertThat(progress.isCancelled).isFalse()

        job.cancel()

        assertThat(progress.isCancelled).isTrue()
    }

    @Test
    fun `nothing bound is nothing cancelled`() {
        assertThat(GitProgress().isCancelled).isFalse()
    }
}
