package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.TaskExtractor
import me.parham1995.notes.markdown.TaskStatuses
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `today-timeline.md` §6 in notes-linux's `docs/specs/`: the same cases by
 * the same names as Daftar for Linux and the Mac, in camelCase.
 *
 * Every fixture here is made up. The repository is public; the vault is not.
 */
class TimelineTest {
    private val day = LocalDate.of(2026, 11, 14)

    private fun at(
        time: String,
        date: LocalDate = day,
    ): Long =
        date
            .atTime(LocalTime.parse(time))
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

    private fun meeting(
        from: String,
        to: String,
        title: String = "Meeting $from",
        date: LocalDate = day,
        tentative: Boolean = false,
    ): WeekMeeting {
        val start = at(from, date)
        var end = at(to, date)
        if (end <= start) end = at(to, date.plusDays(1))
        return WeekMeeting(date, start, end, title, tentative = tentative)
    }

    private fun task(
        text: String,
        key: Long = text.hashCode().toLong(),
        date: LocalDate = day,
    ) = TimelineTask(key, text, date.toString())

    private fun build(
        meetings: List<WeekMeeting> = emptyList(),
        tasks: List<TimelineTask> = emptyList(),
        date: LocalDate = day,
        zone: ZoneId = ZoneOffset.UTC,
        now: Long? = null,
    ) = Timeline.build(date, meetings, tasks, zone, now)

    private fun span(item: TimelineItem) = item.start to item.end

    @Test
    fun aTaskTimeIsReadFromTheStartOfItsText() {
        assertThat(TaskTime.parse("09:30 Call Sara")).isEqualTo(TaskTime(570, 600, "Call Sara"))
        assertThat(TaskTime.parse("9:30-10:15 Gym")).isEqualTo(TaskTime(570, 615, "Gym"))
        assertThat(TaskTime.parse("14:00 – 15:30 Review")).isEqualTo(TaskTime(840, 930, "Review"))
        assertThat(TaskTime.parse("7:15–7:45 Run")).isEqualTo(TaskTime(435, 465, "Run"))

        assertThat(TaskTime.parse("23:50 Late")).isEqualTo(TaskTime(1430, 1440, "Late"))
        assertThat(TaskTime.parse("12:00")).isEqualTo(TaskTime(720, 750, ""))
        assertThat(TaskTime.parse("10:00-09:00 Back")).isEqualTo(TaskTime(600, 630, "Back"))
        // A dash with no time after it is no range: the start counts alone when a space follows it.
        assertThat(TaskTime.parse("10:00 - later")).isEqualTo(TaskTime(600, 630, "- later"))

        for (none in listOf("10:00am Coffee", "9:3 x", "24:00 x", "Call at 09:30", "12:60 x", "", "10:00-x", "10:00-25:00 x")) {
            assertThat(TaskTime.parse(none)).isNull()
        }
    }

    @Test
    fun meetingsAndTasksShareLanesWhenTheyOverlap() {
        val timeline =
            build(
                meetings =
                    listOf(
                        meeting("09:00", "10:00", "A"),
                        meeting("09:30", "10:30", "B"),
                        meeting("10:00", "11:00", "C"),
                    ),
                tasks = listOf(task("11:00 Write")),
            )
        val byTitle = timeline.items.associateBy { it.title }
        assertThat(byTitle.getValue("A").let { it.lane to it.lanes }).isEqualTo(0 to 2)
        assertThat(byTitle.getValue("B").let { it.lane to it.lanes }).isEqualTo(1 to 2)
        assertThat(byTitle.getValue("C").let { it.lane to it.lanes }).isEqualTo(0 to 2)
        assertThat(byTitle.getValue("Write").let { it.lane to it.lanes }).isEqualTo(0 to 1)

        // Of a meeting and a task at the same time, the meeting takes the first lane.
        val same = build(meetings = listOf(meeting("13:00", "13:30", "Sync")), tasks = listOf(task("13:00-13:30 Plan")))
        val sync = same.items.single { it.meeting != null }
        val plan = same.items.single { it.task != null }
        assertThat(sync.lane to sync.lanes).isEqualTo(0 to 2)
        assertThat(plan.lane to plan.lanes).isEqualTo(1 to 2)
    }

    @Test
    fun theAxisCoversTheWorkingDayAndEveryItem() {
        build().let { assertThat(it.axisStart to it.axisEnd).isEqualTo(540 to 1080) }
        build(meetings = listOf(meeting("07:45", "08:20"))).let {
            assertThat(it.axisStart to it.axisEnd).isEqualTo(420 to 1080)
        }
        build(meetings = listOf(meeting("17:30", "19:10"))).let {
            assertThat(it.axisStart to it.axisEnd).isEqualTo(540 to 1200)
        }
        build(now = at("06:10")).let { assertThat(it.axisStart to it.axisEnd).isEqualTo(360 to 1080) }
    }

    @Test
    fun freeTimeIsWhatNothingTakes() {
        val meetings = listOf(meeting("09:00", "10:00"), meeting("12:30", "13:00", tentative = true))
        val tasks = listOf(task("13:00-13:40 Lunch"), task("17:50 Call"))
        assertThat(build(meetings, tasks).free).containsExactly(FreeTime(600, 750), FreeTime(820, 1070)).inOrder()

        val fuller = build(meetings + meeting("10:00", "12:20"), tasks)
        assertThat(fuller.free).containsExactly(FreeTime(820, 1070))
    }

    @Test
    fun aMeetingOverMidnightIsCut() {
        val friday = LocalDate.of(2026, 11, 13)
        val late = meeting("23:00", "01:00", date = friday)
        assertThat(build(listOf(late), date = friday).items.map(::span)).containsExactly(1380 to 1440)
        assertThat(build(listOf(late), date = day).items.map(::span)).containsExactly(0 to 60)

        val earlier = meeting("23:00", "01:00", date = LocalDate.of(2026, 11, 12))
        assertThat(build(listOf(earlier), date = day).items).isEmpty()
        assertThat(build(listOf(earlier), date = day.plusDays(1)).items).isEmpty()
    }

    @Test
    fun theViewersZoneMovesAMeeting() {
        val timeline = build(listOf(meeting("08:00", "09:00")), zone = ZoneId.of("Asia/Tehran"))
        assertThat(timeline.items.map(::span)).containsExactly(690 to 750)
    }

    @Test
    fun nowMarksWhatIsPastAndCurrent() {
        val meetings = listOf(meeting("09:00", "10:00", "First"), meeting("10:00", "11:00", "Second"))
        val today = build(meetings, now = at("10:00"))
        assertThat(today.now).isEqualTo(600)
        val first = today.items.single { it.title == "First" }
        val second = today.items.single { it.title == "Second" }
        assertThat(first.past).isTrue()
        assertThat(first.current).isFalse()
        assertThat(second.past).isFalse()
        assertThat(second.current).isTrue()

        val elsewhere = build(meetings, now = at("10:00", day.plusDays(1)))
        assertThat(elsewhere.now).isNull()
        assertThat(elsewhere.items.none { it.past || it.current }).isTrue()
    }

    @Test
    fun onlyTheDaysOpenTasksWithATime() {
        val after = day.plusDays(1)
        val note =
            """
            - [ ] 10:00 A 📅 $day
            - [x] 11:00 B 📅 $day
            - [ ] 12:00 C 📅 $after
            - [ ] D 📅 $day
            - [ ] 13:00 E ⏳ $day
            - [ ] 14:00 F 📅 $after ⏳ $day
            """.trimIndent()
        val statuses = TaskStatuses.DEFAULT
        val tasks =
            TaskExtractor.extract(MarkdownParser.parseNote(note)).mapIndexed { index, parsed ->
                TimelineTask(index.toLong(), parsed.text, parsed.actionableOn, statuses.isOpen(parsed.status))
            }
        assertThat(build(tasks = tasks).items.map { it.title }).containsExactly("A", "E").inOrder()
    }

    @Test
    fun aLengthIsWrittenShort() {
        assertThat(Timeline.duration(45)).isEqualTo("45m")
        assertThat(Timeline.duration(120)).isEqualTo("2h")
        assertThat(Timeline.duration(90)).isEqualTo("1h 30m")
        assertThat(Timeline.duration(0)).isEqualTo("0m")
    }
}
