package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class TasksQueryTest {
    // A Friday, so "this week" runs Monday 5th to Sunday 11th.
    private val today = LocalDate.of(2026, 10, 9)

    private val tasks =
        listOf(
            QueryTask(
                "Write the report #work",
                TaskState.UNCHECKED,
                "Projects/Alpha.md",
                "Plan",
                scheduled = "2026-10-08",
            ),
            QueryTask("Call the bank", TaskState.UNCHECKED, "Daily/2026-10-09.md", "", due = "2026-10-09"),
            QueryTask(
                "Water the plants",
                TaskState.UNCHECKED,
                "Home.md",
                "Garden",
                scheduled = "2026-10-12",
                recurring = "every week",
            ),
            QueryTask("Read a book #reading/fiction", TaskState.UNCHECKED, "Home.md", "Leisure"),
            QueryTask("Ship the build #work", TaskState.CHECKED, "Projects/Alpha.md", "Plan", done = "2026-10-07"),
            QueryTask("Old idea", TaskState.CANCELLED, "Projects/Beta.md", "Ideas"),
            QueryTask("Half way", TaskState.IN_PROGRESS, "Projects/Beta.md", "Ideas", due = "2026-10-20"),
        )

    private fun run(
        source: String,
        file: String? = null,
    ): List<String> = TasksQuery.parse(source, file).run(tasks, today) { it }.map { it.text }

    @Test
    fun `not done keeps the open and the started`() {
        assertThat(run("not done"))
            .containsExactly(
                "Write the report #work",
                "Call the bank",
                "Water the plants",
                "Half way",
                "Read a book #reading/fiction",
            ).inOrder()
    }

    @Test
    fun `done counts a cancelled task as finished`() {
        assertThat(run("done")).containsExactly("Ship the build #work", "Old idea")
    }

    @Test
    fun `filters on one date, before and after`() {
        assertThat(run("due today")).containsExactly("Call the bank")
        assertThat(run("scheduled before today")).containsExactly("Write the report #work")
        assertThat(run("scheduled after today")).containsExactly("Water the plants")
        assertThat(run("due on or before 2026-10-20")).containsExactly("Call the bank", "Half way")
        assertThat(run("done yesterday")).isEmpty()
        assertThat(run("done 2 days ago")).containsExactly("Ship the build #work")
    }

    @Test
    fun `a week is Monday to Sunday`() {
        assertThat(run("happens this week"))
            .containsExactly("Write the report #work", "Call the bank")
        assertThat(run("happens next week")).containsExactly("Water the plants")
        assertThat(run("due in 2026-10-15 2026-10-31")).containsExactly("Half way")
    }

    @Test
    fun `has and no date`() {
        assertThat(run("not done\nno due date\nno scheduled date")).containsExactly("Read a book #reading/fiction")
        assertThat(run("has due date")).containsExactly("Call the bank", "Half way")
    }

    @Test
    fun `text filters ignore case and keep the value as written`() {
        assertThat(run("path includes projects/")).hasSize(4)
        assertThat(run("not done\npath does not include Projects")).hasSize(3)
        assertThat(run("heading includes PLAN")).hasSize(2)
        assertThat(run("description includes bank")).containsExactly("Call the bank")
        assertThat(run("filename includes Alpha")).hasSize(2)
        assertThat(run("folder includes Daily/")).containsExactly("Call the bank")
    }

    @Test
    fun `tags are matched with or without the hash, nested included`() {
        assertThat(run("tags include #work\nnot done")).containsExactly("Write the report #work")
        assertThat(run("tag includes reading")).containsExactly("Read a book #reading/fiction")
        assertThat(run("not done\ntags do not include #work")).hasSize(4)
    }

    @Test
    fun `status types and recurrence`() {
        assertThat(run("status.type is IN_PROGRESS")).containsExactly("Half way")
        assertThat(run("status.type is not TODO")).hasSize(3)
        assertThat(run("is recurring")).containsExactly("Water the plants")
    }

    @Test
    fun `boolean combinations`() {
        assertThat(run("(due today) OR (scheduled before today)"))
            .containsExactly("Write the report #work", "Call the bank")
        assertThat(run("(not done) AND NOT (path includes Projects)")).hasSize(3)
        assertThat(run("NOT (has due date)\nnot done")).hasSize(3)
        assertThat(run("((due today) OR (is recurring)) AND (not done)"))
            .containsExactly("Call the bank", "Water the plants")
    }

    @Test
    fun `sorts and limits`() {
        assertThat(run("not done\nsort by description\nlimit 2"))
            .containsExactly("Call the bank", "Half way")
            .inOrder()
        assertThat(run("has due date\nsort by due reverse")).containsExactly("Half way", "Call the bank").inOrder()
        assertThat(run("limit to 1 tasks")).hasSize(1)
    }

    @Test
    fun `placeholders stand for the note the query is in`() {
        assertThat(run("path includes {{query.file.path}}", file = "Projects/Beta.md"))
            .containsExactly("Half way", "Old idea")
        assertThat(run("folder includes {{query.file.folder}}", file = "Projects/Gamma.md")).hasSize(4)
    }

    @Test
    fun `says which lines it could not read, and ignores layout`() {
        val query =
            TasksQuery.parse(
                """
                not done
                # a comment
                priority is high
                group by filename
                hide edit button
                short mode
                path includes {{query.file.path}}
                starts before today
                """.trimIndent(),
            )

        assertThat(query.notUnderstood)
            .containsExactly("priority is high", "path includes {{query.file.path}}", "starts before today")
            .inOrder()
    }

    @Test
    fun `a date it cannot read is not a filter that matches nothing`() {
        assertThat(TasksQuery.parse("due before next fortnight").notUnderstood).hasSize(1)
        assertThat(TasksQuery.parse("(due today) AND (priority is high)").notUnderstood).hasSize(1)
        assertThat(TasksQuery.parse("(due today").notUnderstood).hasSize(1)
    }

    @Test
    fun `a dashboard in the shape the plugin's documentation uses`() {
        val board =
            listOf(
                QueryTask(
                    "Prune the roses",
                    TaskState.UNCHECKED,
                    "Lists/Garden.md",
                    "Spring",
                    scheduled = "2026-10-06",
                ),
                QueryTask("Mend the fence", TaskState.IN_PROGRESS, "Lists/Garden.md", ""),
                QueryTask("Finish the novel", TaskState.IN_PROGRESS, "Lists/Reading.md", "Fiction"),
                QueryTask("Buy bread", TaskState.UNCHECKED, "Lists/Errands.md", "خرید"),
                QueryTask("Buy stamps", TaskState.UNCHECKED, "Lists/Errands.md", "Post office"),
                QueryTask("Paint the shed", TaskState.UNCHECKED, "Lists/Garden.md", "Autumn", scheduled = "2026-10-14"),
                QueryTask("Elsewhere", TaskState.UNCHECKED, "Other/Garden.md", "Spring", scheduled = "2026-10-06"),
            )

        fun ask(source: String): List<Pair<List<String>, String>> {
            val query = TasksQuery.parse(source, file = "Lists/Lists.md")
            assertThat(query.notUnderstood).isEmpty()
            return query.run(board, today) { it }.map { query.groupsOf(it) to it.text }
        }

        assertThat(
            ask(
                """
                folder includes {{query.file.folder}}
                filter by function task.status.type === "IN_PROGRESS" && task.file.filenameWithoutExtension != "Reading"
                group by function task.file.filenameWithoutExtension
                group by function task.hasHeading ? task.heading : ''
                show tree
                short mode
                """.trimIndent(),
            ),
        ).containsExactly(listOf("Garden", "") to "Mend the fence")

        assertThat(
            ask(
                """
                scheduled on or before this week
                folder includes {{query.file.folder}}
                filter by function task.status.name === "Todo" && task.file.filenameWithoutExtension != "Reading"
                group by function task.file.filenameWithoutExtension
                """.trimIndent(),
            ),
        ).containsExactly(listOf("Garden") to "Prune the roses")

        assertThat(
            ask(
                """
                not done
                folder includes {{query.file.folder}}
                filter by function !(["Reading", "Errands"].includes(task.file.filenameWithoutExtension))
                group by function task.file.filenameWithoutExtension
                group by function task.hasHeading ? task.heading : ''
                """.trimIndent(),
            ),
        ).containsExactly(
            listOf("Garden", "") to "Mend the fence",
            listOf("Garden", "Autumn") to "Paint the shed",
            listOf("Garden", "Spring") to "Prune the roses",
        ).inOrder()

        assertThat(
            ask(
                """
                not done
                folder includes {{query.file.folder}}
                (path includes Errands) AND (heading includes خرید)
                group by function task.status.name
                """.trimIndent(),
            ),
        ).containsExactly(listOf("Todo") to "Buy bread")
    }

    @Test
    fun `an empty query lists everything`() {
        assertThat(run("")).hasSize(tasks.size)
    }

    @Test
    fun `a limit too large for a number limits nothing rather than throwing`() {
        val query = TasksQuery.parse("limit 99999999999999")

        assertThat(query.limit).isNull()
        assertThat(query.notUnderstood).isEmpty()
    }

    @Test
    fun `a count of days no date can reach is not understood rather than thrown`() {
        listOf("due in 99999999999999999999 days", "due in 999999999999 days", "done 999999999999 days ago").forEach {
            val query = TasksQuery.parse(it)
            assertThat(query.notUnderstood).containsExactly(it)
        }
    }
}
