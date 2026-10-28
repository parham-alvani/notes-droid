package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.StatusType
import me.parham1995.notes.markdown.TaskStatuses
import org.junit.Test

/** The Tasks plugin's `data.json`, for its statuses. */
class TasksPluginTest {
    // The shape the plugin writes, with a custom "In Review" as a vault
    // that reviews its work before it is done has it.
    private val sample =
        """
        {"statusSettings":{"coreStatuses":[
          {"symbol":" ","name":"Todo","nextStatusSymbol":"x","availableAsCommand":true,"type":"TODO"},
          {"symbol":"x","name":"Done","nextStatusSymbol":" ","availableAsCommand":true,"type":"DONE"}],
        "customStatuses":[
          {"symbol":"/","name":"In Progress","nextStatusSymbol":"x","availableAsCommand":true,"type":"IN_PROGRESS"},
          {"symbol":"-","name":"Cancelled","nextStatusSymbol":" ","availableAsCommand":true,"type":"CANCELLED"},
          {"symbol":"r","name":"In Review","nextStatusSymbol":"x","availableAsCommand":false,"type":"IN_PROGRESS"}]}}
        """.trimIndent()

    @Test
    fun `a custom status is read with its name and type`() {
        val statuses = TasksPlugin.statuses(sample)
        assertThat(statuses.all.map { it.symbol }).containsExactly(' ', 'x', '/', '-', 'r').inOrder()
        val review = statuses.of('r')
        assertThat(review.name).isEqualTo("In Review")
        assertThat(review.type).isEqualTo(StatusType.IN_PROGRESS)
        assertThat(review.next).isEqualTo('x')
        assertThat(review.isOpen).isTrue()
        assertThat(statuses.of('>').name).isEqualTo("[>]")
        assertThat(statuses.of('>').type).isEqualTo(StatusType.TODO)
    }

    @Test
    fun `settings without statuses, or with ones it cannot use, give the four defaults`() {
        assertThat(TasksPlugin.statuses("{}")).isEqualTo(TaskStatuses.DEFAULT)
        val odd =
            """{"statusSettings":{"customStatuses":[
              {"symbol":"ab","name":"Two","type":"TODO"},{"symbol":"q","name":"Q","type":"WHATEVER"}]}}"""
        assertThat(TasksPlugin.statuses(odd)).isEqualTo(TaskStatuses.DEFAULT)
    }
}
