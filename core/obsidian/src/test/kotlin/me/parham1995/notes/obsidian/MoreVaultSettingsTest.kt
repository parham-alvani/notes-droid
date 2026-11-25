package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.parham1995.notes.markdown.QueryTask
import me.parham1995.notes.markdown.TaskState
import me.parham1995.notes.markdown.TasksQuery
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * More of a vault's own settings: the tests every Daftar app must have
 * (`more-vault-settings.md` in notes-linux's `docs/specs/`), by the spec's
 * names, where the phone has the feature. `aNewTaskIsScheduled` is in the
 * app's `AddTaskDialogTest`; the phone has no job board, so
 * `theStagesAreTheSettings` has nothing to test here.
 */
class MoreVaultSettingsTest {
    private val wednesday = LocalDate.of(2026, 11, 25)

    @Test
    fun theWorkingDayIsTheSetting() {
        val hours = WorkingHours.parse("08:00-16:30")!!
        val day = Timeline.build(wednesday, emptyList(), emptyList(), ZoneOffset.UTC, hours = hours)
        assertThat(day.free.first().start).isEqualTo(480)
        assertThat(day.free.last().end).isEqualTo(990)
        // A day that ends before it starts is the default.
        assertThat(WorkingHours.parse("18:00-09:00")).isNull()
        val defaults = Timeline.build(wednesday, emptyList(), emptyList(), ZoneOffset.UTC)
        assertThat(defaults.free.first().start).isEqualTo(540)
        assertThat(defaults.free.last().end).isEqualTo(1080)
        // And the allow-list lets only a day that fits through.
        assertThat(
            SettingsFromVault.resolved("[calendar]\nworking_day = \"08:00-16:30\"\n").text("calendar.working_day"),
        ).isEqualTo("08:00-16:30")
        assertThat(
            SettingsFromVault.resolved("[calendar]\nworking_day = \"18:00-09:00\"\n").text("calendar.working_day"),
        ).isNull()
    }

    @Test
    fun theDigestIsFiltered() {
        val due = "2026-11-25"
        val tasks =
            listOf(
                QueryTask("Prepare the call", TaskState.UNCHECKED, "Tasks/Elaheh Interviews.md", "", due = due),
                QueryTask("Pay the rent", TaskState.UNCHECKED, "Tasks/Personal.md", "", due = due),
            )
        val counted = TasksQuery.narrowed(listOf("path does not include Elaheh"), tasks, wednesday) { it }
        assertThat(counted.map { it.path }).containsExactly("Tasks/Personal.md")
        // No lines: every task counts, as before.
        assertThat(TasksQuery.narrowed(emptyList(), tasks, wednesday) { it }).hasSize(2)
    }

    private fun rules(toml: String) = NotificationRule.all(DaftarToml.parse(toml))

    @Test
    fun aRuleRingsOnItsDays() {
        val rule =
            rules(
                """
                [notifications.rules.diary]
                note = "Diet/Diary.md"
                heading = "D MMM YYYY · dddd"
                at = "21:00"
                days = ["mon", "Fri"]
                """.trimIndent(),
            ).single()
        assertThat(rule.ringsOn(LocalDate.of(2026, 11, 23))).isTrue()
        assertThat(rule.ringsOn(LocalDate.of(2026, 11, 24))).isFalse()
        assertThat(
            rules("[notifications.rules.x]\nnote = \"a.md\"\nheading = \"D\"\nat = \"21:00\"\ndays = [\"someday\"]\n"),
        ).isEmpty()
        assertThat(rules("[notifications.rules.x]\nnote = \"a.md\"\nheading = \"D\"\nat = \"21:00\"\ndays = []\n"))
            .isEmpty()
    }

    @Test
    fun aQueryRule() {
        val rule =
            rules("[notifications.rules.late]\nquery = [\"due before tomorrow\", \"not done\"]\nat = \"20:00\"\n")
                .single()
        assertThat(rule.note).isEmpty()
        val tasks =
            listOf(
                QueryTask("Call the bank", TaskState.UNCHECKED, "Tasks/Personal.md", "", due = "2026-11-24"),
                QueryTask("Send the form", TaskState.UNCHECKED, "Tasks/Personal.md", "", due = "2026-11-25"),
                QueryTask("Book a table", TaskState.UNCHECKED, "Tasks/Personal.md", "", due = "2026-12-01"),
                QueryTask("Filed", TaskState.CHECKED, "Tasks/Personal.md", "", due = "2026-11-20"),
            )
        val found = TasksQuery.parse(rule.query!!.joinToString("\n")).run(tasks, wednesday) { it }
        assertThat(rule.queryBody(found.map { it.text })).isEqualTo("2 tasks: Call the bank, Send the form")
        assertThat(rule.queryBody(emptyList())).isNull()
        // A query rule with a heading too has two kinds, and is no rule.
        assertThat(
            rules("[notifications.rules.x]\nquery = [\"not done\"]\nheading = \"D\"\nat = \"20:00\"\n"),
        ).isEmpty()
    }

    @Test
    fun aQuietRule() {
        val rule =
            rules("[notifications.rules.log]\nnote = \"Log.md\"\nquiet_days = 14\nat = \"09:00\"\n").single()
        assertThat(rule.at).isEqualTo(LocalTime.of(9, 0))
        assertThat(rule.isQuiet(wednesday.minusDays(15), wednesday)).isTrue()
        assertThat(rule.quietBody(wednesday.minusDays(15), wednesday)).isEqualTo("No change for 15 days")
        assertThat(rule.isQuiet(wednesday.minusDays(13), wednesday)).isFalse()
        // A quiet rule needs its note.
        assertThat(rules("[notifications.rules.log]\nquiet_days = 14\nat = \"09:00\"\n")).isEmpty()
    }

    @Test
    fun theNearestFolderWins() {
        val rules =
            FolderRules.parse(
                """
                [folders."Companies"]
                folded = false

                [folders."Companies/Snapp"]
                template = "T.md"
                """.trimIndent(),
            )
        val roles = rules.forNote("Companies/Snapp/Roles/SRE.md")
        assertThat(roles.folded).isFalse()
        assertThat(roles.template).isEqualTo("T.md")
        val personal = rules.forNote("Personal/Journal.md")
        assertThat(personal.folded).isNull()
        assertThat(personal.template).isNull()
    }

    @Test
    fun `a folder may hold a dot, and its direction is read`() {
        val rules = FolderRules.parse("[folders.\"Notes v1.2\"]\ndirection = \"rtl\"\n")
        assertThat(rules.forNote("Notes v1.2/a.md").direction).isEqualTo(FolderDirection.RTL)
        assertThat(rules.forNote("a.md").direction).isNull()
    }

    @Test
    fun `every new key is one a vault may set`() {
        listOf(
            "files.attachments",
            "files.ignore",
            "daily.format",
            "templates.folder",
            "home.greeting",
            "home.clock",
            "properties.types.job_next",
            "tasks.statuses.in_review.symbol",
            "calendar.working_day",
            "tasks.default_scheduled",
            "notifications.digest_filter",
            "jobs.waiting",
            "jobs.closed",
            "notifications.rules.x.days",
            "notifications.rules.x.query",
            "notifications.rules.x.quiet_days",
            "folders.Companies/Snapp.direction",
            "folders.Companies.folded",
            "folders.Companies.template",
        ).forEach { assertWithMessage(it).that(SettingsFromVault.mayBeSet(it)).isTrue() }
    }
}
