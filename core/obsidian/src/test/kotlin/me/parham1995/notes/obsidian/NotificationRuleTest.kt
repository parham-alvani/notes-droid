package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Notification rules: `notification-rules.md` in notes-linux's `docs/specs/`,
 * section 4, by the test names the spec gives.
 */
class NotificationRuleTest {
    private val diary =
        """
        [notifications.rules.diary]
        note = "Diet/Diary.md"
        heading = "D MMM YYYY · dddd"
        at = "21:00"
        """.trimIndent()

    private val day = LocalDate.of(2026, 11, 25)

    private fun rules(toml: String) = NotificationRule.all(DaftarToml.parse(toml))

    private val rule = NotificationRule("diary", "Diet/Diary.md", "D MMM YYYY · dddd", LocalTime.of(21, 0))

    @Test
    fun aRuleIsReadFromItsSection() {
        val read = rules(diary).single()

        assertThat(read.id).isEqualTo("diary")
        assertThat(read.note).isEqualTo("Diet/Diary.md")
        assertThat(read.at).isEqualTo(LocalTime.of(21, 0))
        assertThat(read.alertTitle).isEqualTo("Diary")
        assertThat(read.message).isNull()
    }

    @Test
    fun aRuleThatDoesNotFitIsIgnored() {
        val good = "\n[notifications.rules.good]\nnote = \"Log.md\"\nheading = \"YYYY-MM-DD\"\nat = \"07:30\"\n"
        val bad =
            listOf(
                "[notifications.rules.diary]\nnote = \"Diet/Diary.md\"\nheading = \"D MMM YYYY\"\n",
                "[notifications.rules.diary]\nnote = \"Diet/Diary.md\"\nheading = \"D MMM YYYY\"\nat = \"25:00\"\n",
                "[notifications.rules.diary]\nnote = \"../x.md\"\nheading = \"D MMM YYYY\"\nat = \"21:00\"\n",
                "[notifications.rules.\"my diary\"]\nnote = \"Diet/Diary.md\"\nheading = \"D MMM YYYY\"\nat = \"21:00\"\n",
            )
        bad.forEach { toml ->
            assertThat(rules(toml)).isEmpty()
            assertThat(rules(toml + good).map { it.id }).containsExactly("good")
        }
    }

    @Test
    fun theExpectedHeadingIsTheDayInTheFormat() {
        assertThat(rule.expected(day)).isEqualTo("25 Nov 2026 · Wednesday")
        assertThat(rule.copy(heading = "[Day] YYYY-MM-DD").expected(day)).isEqualTo("Day 2026-11-25")
    }

    @Test
    fun todaysHeadingIsFound() {
        assertThat(rule.isMissing("## 24 Nov 2026 · Tuesday\n", day)).isTrue()
        assertThat(rule.isMissing("### 25 Nov 2026 · Wednesday\n", day)).isFalse()
        assertThat(rule.isMissing("## 25 Nov 2026 · Wednesday (fasting)\n", day)).isFalse()
        assertThat(rule.isMissing("25 Nov 2026 · Wednesday\n", day)).isTrue()
        assertThat(rule.isMissing("```text\n### 25 Nov 2026 · Wednesday\n```\n", day)).isTrue()
        assertThat(rule.isMissing("", day)).isTrue()
        // A note that is not there has no heading either.
        assertThat(rule.isMissing(null, day)).isTrue()
    }

    @Test
    fun theAlertSaysWhatIsMissing() {
        assertThat(rule.body(day)).isEqualTo("No heading for today yet: 25 Nov 2026 · Wednesday")
        assertThat(rule.alertTitle).isEqualTo("Diary")

        val said = rule.copy(title = "Food", message = "Nothing is logged in the diary today.")
        assertThat(said.alertTitle).isEqualTo("Food")
        assertThat(said.body(day)).isEqualTo("Nothing is logged in the diary today.")
    }

    @Test
    fun aVaultMaySetARule() {
        val settings = SettingsFromVault.resolved(diary)

        assertThat(NotificationRule.all(settings)).containsExactly(rule)
        listOf("note", "heading", "at").forEach {
            assertThat(settings.from("notifications.rules.diary.$it")).isEqualTo(SettingSource.VAULT)
        }
        assertThat(settings.refused).isEmpty()
        // A note that leaves the vault is not taken from it, so there is no rule.
        val outside = SettingsFromVault.resolved(diary.replace("Diet/Diary.md", "/etc/passwd"))
        assertThat(NotificationRule.all(outside)).isEmpty()
    }
}
