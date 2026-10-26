package me.parham1995.notes.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.parham1995.notes.R
import me.parham1995.notes.data.TaskBucket
import me.parham1995.notes.data.calendar.TodayCalendar
import me.parham1995.notes.markdown.TaskState
import me.parham1995.notes.obsidian.CalendarWeeks
import me.parham1995.notes.obsidian.WeekMeeting
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.theme.Accents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the day holds in numbers, from the task groups the list below already has. */
internal data class TodayCounts(
    val overdue: Int = 0,
    val due: Int = 0,
    val inProgress: Int = 0,
    /** Tomorrow and the rest of the week. */
    val coming: Int = 0,
) {
    companion object {
        fun of(groups: List<TaskGroup>): TodayCounts {
            fun count(bucket: TaskBucket) = groups.firstOrNull { it.bucket == bucket }?.rows?.size ?: 0
            return TodayCounts(
                overdue = count(TaskBucket.OVERDUE),
                due = count(TaskBucket.TODAY),
                inProgress = groups.sumOf { group -> group.rows.count { it.state == TaskState.IN_PROGRESS.name } },
                coming = count(TaskBucket.TOMORROW) + count(TaskBucket.THIS_WEEK),
            )
        }
    }
}

/**
 * Today, at the top of the task list: the date, what the day holds in a line
 * of chips, the meetings from the week note with the one on now marked and a
 * button to join it, and how tomorrow starts. The Mac's Today view, folded
 * into the tab whose list already holds the rest of it -- the overdue, due and
 * coming tasks are the groups right under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayCard(
    calendar: TodayCalendar,
    counts: TodayCounts,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // The meeting on now changes as the day goes; a minute is fine-grained enough.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS)
            now = System.currentTimeMillis()
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val day = calendar.today
    val blue = Accents.blue

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(blue.copy(alpha = WASH))
            .border(1.dp, blue.copy(alpha = EDGE), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("today"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)),
            style = MaterialTheme.typography.headlineSmall.inScript(),
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val meetings = day?.meetings?.size ?: 0
            Chip(pluralStringResource(R.plurals.today_meetings, meetings, meetings), "calendar", blue)
            day?.free?.let { free ->
                Chip(
                    if (free ==
                        "none"
                    ) {
                        stringResource(R.string.today_free_none)
                    } else {
                        stringResource(R.string.today_free, free)
                    },
                    "clock",
                    Accents.springGreen,
                )
            }
            Chip(pluralStringResource(R.plurals.today_due, counts.due, counts.due), "sun", Accents.orange)
            if (counts.overdue > 0) {
                Chip(
                    pluralStringResource(R.plurals.today_overdue, counts.overdue, counts.overdue),
                    "circle-alert",
                    Accents.red,
                )
            }
            if (counts.inProgress > 0) {
                Chip(
                    pluralStringResource(R.plurals.today_in_progress, counts.inProgress, counts.inProgress),
                    "circle-dot",
                    Accents.purple,
                )
            }
            if (counts.coming > 0) {
                Chip(
                    pluralStringResource(R.plurals.today_coming, counts.coming, counts.coming),
                    "calendar-clock",
                    Accents.aqua,
                )
            }
        }
        when {
            day == null ->
                Quiet(stringResource(R.string.today_no_week_note, CalendarWeeks.DEFAULT_FOLDER))
            day.meetings.isEmpty() -> Quiet(stringResource(R.string.today_no_meetings))
            else -> day.meetings.forEach { MeetingRow(it, now, time, zone) }
        }
        calendar.tomorrowStarts?.let { first ->
            HorizontalDivider(color = blue.copy(alpha = EDGE))
            Quiet(stringResource(R.string.today_tomorrow, first.start.at(time, zone), first.title))
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.inScript(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Chip(
    text: String,
    icon: String,
    color: Color,
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = CHIP_WASH))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideGlyph(icon, size = 14.dp, tint = color)
        Text(text, style = MaterialTheme.typography.labelMedium.inScript(), color = color)
    }
}

@Composable
private fun MeetingRow(
    meeting: WeekMeeting,
    now: Long,
    time: DateTimeFormatter,
    zone: ZoneId,
) {
    val end = meeting.end
    val start = meeting.start
    val past = end != null && end < now
    val live = !past && start != null && start <= now
    val color =
        when {
            live -> Accents.orange
            past -> MaterialTheme.colorScheme.outline
            else -> Accents.blue
        }
    Row(
        Modifier.fillMaxWidth().alpha(if (past) PAST_ALPHA else 1f),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A bar in the meeting's state: on now, coming, or done.
        Box(
            Modifier
                .width(4.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        if (meeting.allDay) {
                            stringResource(R.string.today_all_day)
                        } else {
                            "${meeting.start.at(time, zone)}–${meeting.end.at(time, zone)}"
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    fontWeight = if (live) FontWeight.SemiBold else null,
                )
                if (live) Badge(stringResource(R.string.today_now), Accents.orange)
                if (meeting.tentative) Badge(stringResource(R.string.today_maybe), Accents.purple)
            }
            Text(
                text = meeting.title,
                style = MaterialTheme.typography.bodyMedium.inScript(),
                fontWeight = if (live) FontWeight.SemiBold else null,
                color =
                    if (meeting.busyOnly ||
                        past
                    ) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            meeting.overlaps?.let {
                Text(
                    text = stringResource(R.string.today_overlaps, it),
                    style = MaterialTheme.typography.labelSmall.inScript(),
                    color = Accents.red,
                )
            }
        }
        val join = meeting.join
        if (join != null && !past) {
            val uri = LocalUriHandler.current
            FilledTonalButton(onClick = { runCatching { uri.openUri(join) } }) {
                LucideGlyph("video", size = 16.dp)
                Text(stringResource(R.string.today_join), Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun Badge(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.inScript(),
        color = color,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(color.copy(alpha = CHIP_WASH))
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private fun Long?.at(
    time: DateTimeFormatter,
    zone: ZoneId,
): String = this?.let { Instant.ofEpochMilli(it).atZone(zone).format(time) }.orEmpty()

private const val MINUTE_MS = 60_000L
private const val WASH = 0.08f
private const val EDGE = 0.25f
private const val CHIP_WASH = 0.16f
private const val PAST_ALPHA = 0.6f
