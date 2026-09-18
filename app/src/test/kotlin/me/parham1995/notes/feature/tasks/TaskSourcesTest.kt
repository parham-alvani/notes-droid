package me.parham1995.notes.feature.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How the task filter names the files it offers.
 *
 * The vault has 110 duplicated basenames in it, so a filter built on the file
 * name alone offers the same word twice with different counts and no way to
 * tell which is which -- which is exactly what it did.
 */
class TaskSourcesTest {
    @Test
    fun `a file that shares its name is qualified by its folder`() {
        val sources =
            taskSources(
                mapOf(
                    "Learning/Kafka/Best Practices.md" to 23,
                    "Learning/Postgres/Best Practices.md" to 30,
                ),
            )

        assertThat(sources.map { it.name })
            .containsExactly("Kafka / Best Practices", "Postgres / Best Practices")
    }

    @Test
    fun `a file with a name of its own keeps it`() {
        // "Tasks / Nobitex" is noise when there is only one Nobitex.
        val sources = taskSources(mapOf("Tasks/Nobitex.md" to 47))

        assertThat(sources.single().name).isEqualTo("Nobitex")
    }

    @Test
    fun `a file at the root cannot be qualified and is left alone`() {
        val sources = taskSources(mapOf("Nobitex.md" to 1, "Tasks/Nobitex.md" to 2))

        assertThat(sources.map { it.name }).containsExactly("Nobitex", "Tasks / Nobitex")
    }

    @Test
    fun `names sort together regardless of case`() {
        val sources = taskSources(mapOf("a/zebra.md" to 1, "a/Apple.md" to 1, "a/mango.md" to 1))

        assertThat(sources.map { it.name }).containsExactly("Apple", "mango", "zebra").inOrder()
    }

    @Test
    fun `the count is carried through untouched`() {
        assertThat(taskSources(mapOf("Tasks/Personal.md" to 9)).single().count).isEqualTo(9)
    }
}
