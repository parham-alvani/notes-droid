package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A vault's statuses, put together from what its settings list. */
class TaskStatusesTest {
    @Test
    fun `with nothing listed, the four defaults in order`() {
        assertThat(TaskStatuses.DEFAULT.all.map { it.symbol }).containsExactly(' ', 'x', '/', '-').inOrder()
        assertThat(TaskStatuses.DEFAULT.of('X').type).isEqualTo(StatusType.DONE)
    }

    @Test
    fun `a symbol not listed is to do, named by itself`() {
        val forwarded = TaskStatuses.DEFAULT.of('>')
        assertThat(forwarded.type).isEqualTo(StatusType.TODO)
        assertThat(forwarded.name).isEqualTo("[>]")
        assertThat(forwarded.isOpen).isTrue()
    }

    @Test
    fun `missing defaults come before the custom ones, and the first of a symbol wins`() {
        val statuses =
            TaskStatuses.of(
                core = listOf(TaskStatus(' ', "Open", 'x', StatusType.TODO)),
                custom =
                    listOf(
                        TaskStatus('r', "In Review", 'x', StatusType.IN_PROGRESS),
                        TaskStatus('r', "Again", 'x', StatusType.DONE),
                        TaskStatus('n', "Note", ' ', StatusType.NON_TASK),
                    ),
            )
        assertThat(statuses.all.map { it.symbol }).containsExactly(' ', 'x', '/', '-', 'r', 'n').inOrder()
        assertThat(statuses.of(' ').name).isEqualTo("Open")
        assertThat(statuses.of('r').name).isEqualTo("In Review")
        assertThat(statuses.isOpen('r')).isTrue()
        assertThat(statuses.isOpen('n')).isFalse()
        assertThat(statuses.of('n').type.state).isEqualTo(TaskState.CANCELLED)
    }
}
