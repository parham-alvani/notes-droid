package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskExpressionTest {
    private val task =
        QueryTask(
            text = "Fix the gate",
            state = TaskState.IN_PROGRESS,
            path = "Plans/Garden.md",
            section = "Fences",
        )

    private fun eval(source: String): Any? = TaskExpression.parse(source)!!.evaluate(task)

    @Test
    fun `reads the properties the index keeps, by the plugin's names`() {
        assertThat(eval("task.status.type")).isEqualTo("IN_PROGRESS")
        assertThat(eval("task.status.name")).isEqualTo("In Progress")
        assertThat(eval("task.file.filenameWithoutExtension")).isEqualTo("Garden")
        assertThat(eval("task.file.filename")).isEqualTo("Garden.md")
        assertThat(eval("task.file.folder")).isEqualTo("Plans/")
        assertThat(eval("task.heading")).isEqualTo("Fences")
        assertThat(eval("task.hasHeading")).isEqualTo(true)
        assertThat(eval("task.description")).isEqualTo("Fix the gate")
    }

    @Test
    fun `compares, combines and negates`() {
        assertThat(
            eval("""task.status.type === "IN_PROGRESS" && task.file.filenameWithoutExtension != "Reading""""),
        ).isEqualTo(true)
        assertThat(eval("""task.status.name === "Todo" || task.heading == 'Fences'""")).isEqualTo(true)
        assertThat(eval("""!(task.status.name !== "In Progress")""")).isEqualTo(true)
        assertThat(eval("""task.status.name === "Todo" && task.heading == 'Fences'""")).isEqualTo(false)
    }

    @Test
    fun `an array includes, and a string does too`() {
        assertThat(eval("""["Reading", "Garden"].includes(task.file.filenameWithoutExtension)""")).isEqualTo(true)
        assertThat(eval("""!(["Reading", "Errands"].includes(task.file.filenameWithoutExtension))""")).isEqualTo(true)
        assertThat(eval("""task.description.toLowerCase().includes("gate")""")).isEqualTo(true)
        assertThat(eval("""task.file.path.startsWith("Plans/")""")).isEqualTo(true)
    }

    @Test
    fun `a ternary picks a value, and a missing heading is empty`() {
        val noHeading = task.copy(section = "")
        val expression = TaskExpression.parse("task.hasHeading ? task.heading : ''")!!

        assertThat(expression.evaluate(task)).isEqualTo("Fences")
        assertThat(expression.evaluate(noHeading)).isEqualTo("")
    }

    @Test
    fun `anything outside the subset is refused, not guessed`() {
        assertThat(TaskExpression.parse("task.priority.number > 2")).isNull()
        assertThat(TaskExpression.parse("task.due.moment.isBefore(x)")).isNull()
        assertThat(TaskExpression.parse("task.urgency")).isNull()
        assertThat(TaskExpression.parse("""task.status.type === "DONE" &&""")).isNull()
        assertThat(TaskExpression.parse("""task.description.replace("a", "b")""")).isNull()
        assertThat(TaskExpression.parse("""task.description === "unterminated""")).isNull()
        assertThat(TaskExpression.parse("")).isNull()
    }
}
