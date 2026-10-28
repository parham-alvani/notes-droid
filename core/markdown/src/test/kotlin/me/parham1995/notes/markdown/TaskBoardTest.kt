package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.TaskBoard.Drop
import me.parham1995.notes.markdown.TaskBoard.Grouping
import org.junit.Test
import java.time.LocalDate

/** The board's columns, by the rules the Mac app's board keeps. */
class TaskBoardTest {
    private val today = LocalDate.of(2026, 10, 25)

    /** A task as the index would read [raw], in [path] on [line]. */
    private fun task(
        raw: String,
        path: String = "Tasks/Work.md",
        line: Int = 0,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): Pair<String, TaskBoard.Facts> {
        val parsed = TaskExtractor.extract(MarkdownParser.parseNote(raw)).single()
        val facts =
            TaskBoard.Facts(
                state = parsed.state,
                path = path,
                title = path.substringAfterLast('/').removeSuffix(".md"),
                line = line,
                day = parsed.actionableOn,
                priority = parsed.priority,
                done = parsed.done,
                cancelled = parsed.cancelled,
                status = statuses.of(parsed.status),
                reason = parsed.reason,
            )
        return parsed.text to facts
    }

    private fun board(
        tasks: List<Pair<String, TaskBoard.Facts>>,
        by: Grouping,
        closedWithin: Int? = TaskBoard.CLOSED_WITHIN,
    ) = TaskBoard.columns(tasks, by, today, closedWithin) { it.second }

    private fun List<TaskBoard.Column<Pair<String, TaskBoard.Facts>>>.texts() =
        map { column ->
            column.tasks.map {
                it.first
            }
        }

    @Test
    fun `status columns keep only what closed in the last two weeks`() {
        val tasks =
            listOf(
                task("- [ ] Write 📅 2026-10-27", line = 0),
                task("- [ ] Urgent ⏫ 📅 2026-10-27", line = 1),
                task("- [/] Build", line = 2),
                task("- [x] Shipped ✅ 2026-10-20", line = 3),
                task("- [x] Long ago ✅ 2026-01-01", line = 4),
                task("- [-] Dropped ❌ 2026-10-24", line = 5),
                task("- [>] Forwarded", line = 6),
                task("- [x] Undated done", line = 7),
            )
        val columns = board(tasks, Grouping.STATUS)

        assertThat(columns.map { it.id }).containsExactly("todo", "doing", "done", "cancelled").inOrder()
        // The same day: the higher priority first. Any open status but `/` is to do.
        assertThat(columns.texts())
            .containsExactly(
                listOf("Urgent", "Write", "Forwarded"),
                listOf("Build"),
                listOf("Shipped"),
                listOf("Dropped"),
            ).inOrder()
        assertThat(columns.map { it.drop })
            .containsExactly(Drop.Status(' '), Drop.Status('/'), Drop.Status('x'), Drop.Status('-'))
            .inOrder()
        // Every closed task with the toggle, the last one closed first.
        assertThat(board(tasks, Grouping.STATUS, closedWithin = null)[2].tasks.map { it.first })
            .containsExactly("Shipped", "Long ago", "Undated done")
            .inOrder()
    }

    @Test
    fun `the last one closed comes first`() {
        val tasks =
            listOf(
                task("- [x] Older ✅ 2026-10-12", line = 0),
                task("- [x] Newer ✅ 2026-10-24", line = 1),
                task("- [x] Exactly two weeks ✅ 2026-10-11", line = 2),
                task("- [x] A day too old ✅ 2026-10-10", line = 3),
            )
        assertThat(board(tasks, Grouping.STATUS)[2].tasks.map { it.first })
            .containsExactly("Newer", "Older", "Exactly two weeks")
            .inOrder()
    }

    @Test
    fun `day columns, open tasks only, filed by due else scheduled`() {
        val tasks =
            listOf(
                task("- [ ] Late 📅 2026-10-20"),
                task("- [ ] Now ⏳ 2026-10-25"),
                task("- [ ] Due wins ⏳ 2026-10-20 📅 2026-10-26"),
                task("- [ ] Soon 📅 2026-11-01"),
                task("- [ ] Far 📅 2026-12-01"),
                task("- [ ] Whenever"),
                task("- [x] Done 📅 2026-10-25"),
            )
        val columns = board(tasks, Grouping.DAY)

        assertThat(
            columns.map { it.id },
        ).containsExactly("overdue", "today", "tomorrow", "week", "later", "none").inOrder()
        assertThat(columns.texts())
            .containsExactly(
                listOf("Late"),
                listOf("Now"),
                listOf("Due wins"),
                listOf("Soon"),
                listOf("Far"),
                listOf("Whenever"),
            ).inOrder()
        assertThat(columns.map { it.drop })
            .containsExactly(
                null,
                Drop.Day("2026-10-25"),
                Drop.Day("2026-10-26"),
                Drop.Day("2026-11-01"),
                null,
                Drop.Day(null),
            ).inOrder()
    }

    @Test
    fun `open tasks sort by day, then priority, then where they are`() {
        val tasks =
            listOf(
                task("- [ ] No date, highest 🔺", path = "A.md", line = 0),
                task("- [ ] Lowest ⏬ 📅 2026-10-25", path = "A.md", line = 1),
                task("- [ ] Low 🔽 📅 2026-10-25", path = "A.md", line = 2),
                task("- [ ] Plain, B 📅 2026-10-25", path = "B.md", line = 0),
                task("- [ ] Plain, A 📅 2026-10-25", path = "A.md", line = 9),
                task("- [ ] Medium 🔼 📅 2026-10-25", path = "A.md", line = 3),
                task("- [ ] High ⏫ 📅 2026-10-25", path = "A.md", line = 4),
                task("- [ ] Highest 🔺 📅 2026-10-25", path = "A.md", line = 5),
                task("- [ ] Earlier 📅 2026-10-24", path = "Z.md", line = 0),
            )
        assertThat(board(tasks, Grouping.STATUS)[0].tasks.map { it.first })
            .containsExactly(
                "Earlier",
                "Highest",
                "High",
                "Medium",
                "Plain, A",
                "Plain, B",
                "Low",
                "Lowest",
                "No date, highest",
            ).inOrder()
    }

    @Test
    fun `a file column for each note with open tasks, by title`() {
        val tasks =
            listOf(
                task("- [ ] B", path = "Tasks/Zeta.md"),
                task("- [ ] A", path = "Tasks/alpha.md"),
                task("- [x] C", path = "Tasks/Beta.md"),
            )
        val columns = board(tasks, Grouping.FILE)

        assertThat(columns.map { it.title }).containsExactly("alpha", "Zeta").inOrder()
        assertThat(columns.map { it.id }).containsExactly("Tasks/alpha.md", "Tasks/Zeta.md").inOrder()
        assertThat(columns.map { it.drop }).containsExactly(null, null)
    }

    @Test
    fun `only the columns that take a drop are offered as moves`() {
        assertThat(
            TaskBoard.drops(Grouping.DAY, today).map {
                it.id
            },
        ).containsExactly("today", "tomorrow", "week", "none").inOrder()
        assertThat(TaskBoard.drops(Grouping.STATUS, today).map { it.id })
            .containsExactly("todo", "doing", "done", "cancelled")
            .inOrder()
        assertThat(TaskBoard.drops(Grouping.FILE, today)).isEmpty()
    }

    @Test
    fun `the index reads a priority mark`() {
        assertThat(task("- [ ] Ship ⏫ 📅 2026-10-27").second.priority).isEqualTo("⏫")
        assertThat(task("- [ ] Ship").second.priority).isNull()
        assertThat(task("- [-] Drop ❌ 2026-10-24").second.cancelled).isEqualTo("2026-10-24")
    }

    @Test
    fun `every status under way shares the In Progress column, and keeps its symbol there`() {
        val statuses =
            TaskStatuses.of(
                emptyList(),
                listOf(
                    TaskStatus('r', "In Review", 'x', StatusType.IN_PROGRESS),
                    TaskStatus('n', "Note", ' ', StatusType.NON_TASK),
                ),
            )
        val tasks =
            listOf(
                task("- [/] Build", line = 0, statuses = statuses),
                task("- [r] Ship [reason:: waiting on [[Amir]]]", line = 1, statuses = statuses),
                task("- [n] An aside", line = 2, statuses = statuses),
            )
        val columns = board(tasks, Grouping.STATUS, closedWithin = null)
        assertThat(
            columns.texts(),
        ).containsExactly(listOf<String>(), listOf("Build", "Ship"), listOf<String>(), listOf<String>())
            .inOrder()
        val review = tasks[1].second
        assertThat(review.reason).isEqualTo("waiting on [[Amir]]")
        assertThat(TaskBoard.statusFor(review, Drop.Status('/'), statuses)).isNull()
        assertThat(TaskBoard.statusFor(review, Drop.Status('-'), statuses)).isEqualTo('-')
    }
}
