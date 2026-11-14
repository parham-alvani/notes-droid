package me.parham1995.notes.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.parham1995.notes.R
import me.parham1995.notes.data.calendar.TodayCalendar
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.obsidian.DayTimeline
import me.parham1995.notes.obsidian.FreeTime
import me.parham1995.notes.obsidian.Timeline
import me.parham1995.notes.obsidian.TimelineItem
import me.parham1995.notes.obsidian.TimelineTask
import me.parham1995.notes.obsidian.WeekMeeting
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.theme.Accents
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The day's meetings and its timed tasks on one axis of hours, with the free
 * time between them and a line at now: `today-timeline.md` §5 in
 * notes-linux's `docs/specs/`.
 *
 * Its own card under [TodayCard], and only when the day has a week note or a
 * task with a time; the meetings card above stays as it is, with its join
 * buttons. A minute is a dp, so an hour is a thumb's height and the working
 * day fits a phone's screen; the card never scrolls on its own.
 *
 * [tasks] are the open tasks the list already has; the ones of [today] whose
 * text starts with a time are drawn. A task's box ticks through [onComplete]
 * when [canWrite], the same toggle the list's checkbox is, and opens through
 * [onOpen].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayTimeline(
    calendar: TodayCalendar,
    tasks: List<TaskRow>,
    canWrite: Boolean,
    onComplete: (TaskRow) -> Unit,
    onOpen: (TaskRow) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    clock: () -> Long = System::currentTimeMillis,
) {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS)
            now = clock()
        }
    }
    val rows = remember(tasks) { tasks.associateBy { it.id } }
    val timeline =
        remember(calendar, tasks, today, zone, now) {
            Timeline.build(
                date = today,
                meetings = calendar.yesterday?.meetings.orEmpty() + calendar.today?.meetings.orEmpty(),
                tasks = tasks.map { TimelineTask(it.id, it.text, it.actionableOn) },
                zone = zone,
                now = now,
                hours = calendar.hours,
            )
        }
    if (calendar.today == null && timeline.items.none { it.task != null }) return

    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val edge = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, edge, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("timeline"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.today_timeline),
            style = MaterialTheme.typography.titleSmall.inScript(),
            fontWeight = FontWeight.SemiBold,
        )
        if (timeline.allDay.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                timeline.allDay.forEach { AllDayChip(it) }
            }
        }
        Axis(timeline, rows, canWrite, onComplete, onOpen, time, zone)
    }
}

@Composable
private fun AllDayChip(meeting: WeekMeeting) {
    val blue = Accents.blue
    Text(
        text = meeting.title,
        style = MaterialTheme.typography.labelMedium.inScript(),
        color = blue,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(blue.copy(alpha = BOX_WASH))
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The hours, the free bands, the boxes and the line at now. */
@Composable
private fun Axis(
    timeline: DayTimeline,
    rows: Map<Long, TaskRow>,
    canWrite: Boolean,
    onComplete: (TaskRow) -> Unit,
    onOpen: (TaskRow) -> Unit,
    time: DateTimeFormatter,
    zone: ZoneId,
) {
    val label = MaterialTheme.typography.labelSmall
    val line = lineHeight(MaterialTheme.typography.labelMedium.inScript())
    // Half a line above the first hour and below the last, so their labels are not cut.
    val margin = lineHeight(label) / 2
    val span = timeline.axisEnd - timeline.axisStart
    val red = Accents.red

    fun y(minute: Int): Dp = MINUTE * (minute - timeline.axisStart) + margin

    Box(Modifier.fillMaxWidth().height(MINUTE * span + margin * 2)) {
        // The hours, on the left, with a line across for each.
        for (hour in timeline.axisStart / HOUR..timeline.axisEnd / HOUR) {
            val at = y(hour * HOUR)
            Text(
                text = clockOf(hour * HOUR).format(time),
                style = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.offset(y = at - margin).width(GUTTER - 4.dp),
            )
            Box(
                Modifier
                    .padding(start = GUTTER)
                    .offset(y = at)
                    .fillMaxWidth()
                    .height(HAIRLINE)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }

        BoxWithConstraints(
            Modifier
                .padding(start = GUTTER)
                .fillMaxWidth()
                .height(MINUTE * span + margin * 2),
        ) {
            val width = maxWidth
            timeline.free.forEach { FreeBand(it, y(it.start), line) }
            // In order of start, so a later box is drawn over an earlier one it overlaps.
            timeline.items.forEach { item ->
                val laneWidth = width / item.lanes
                val box =
                    Modifier
                        .offset(x = laneWidth * item.lane, y = y(item.start))
                        .width(laneWidth - LANE_GAP)
                        .height(maxOf(MINUTE * (item.end - item.start), line + BOX_PADDING * 2))
                val meeting = item.meeting
                val row = item.task?.let { rows[it.key] }
                when {
                    meeting != null -> MeetingBox(item, meeting, box, line, time, zone)
                    row != null ->
                        TaskBox(
                            item = item,
                            row = row,
                            onComplete = if (canWrite) ({ onComplete(row) }) else null,
                            onOpen = { onOpen(row) },
                            modifier = box,
                            line = line,
                            time = time,
                        )
                }
            }
            timeline.now?.let { minute ->
                val at = y(minute)
                Box(
                    Modifier
                        .offset(y = at - NOW_LINE / 2)
                        .fillMaxWidth()
                        .height(NOW_LINE)
                        .background(red),
                )
                Box(
                    Modifier
                        .offset(x = -NOW_DOT / 2, y = at - NOW_DOT / 2)
                        .size(NOW_DOT)
                        .clip(CircleShape)
                        .background(red)
                        .testTag("timeline-now"),
                )
            }
        }
    }
}

@Composable
private fun FreeBand(
    free: FreeTime,
    top: Dp,
    line: Dp,
) {
    val green = Accents.springGreen
    val height = MINUTE * free.minutes
    Box(
        Modifier
            .offset(y = top)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(green.copy(alpha = FREE_WASH)),
    ) {
        if (height >= line + BOX_PADDING * 2) {
            Text(
                text = stringResource(R.string.today_free, Timeline.duration(free.minutes)),
                style = MaterialTheme.typography.labelSmall.inScript(),
                color = green,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center).padding(BOX_PADDING),
            )
        }
    }
}

/**
 * A meeting: blue, a tentative one fainter with a dashed edge, a busy-only one
 * grey. A tap joins it when it has a link; holding it shows its calendars and
 * what it overlaps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeetingBox(
    item: TimelineItem,
    meeting: WeekMeeting,
    modifier: Modifier,
    line: Dp,
    time: DateTimeFormatter,
    zone: ZoneId,
) {
    val uri = LocalUriHandler.current
    var details by remember { mutableStateOf(false) }
    val color = if (meeting.busyOnly) Accents.grey else Accents.blue
    val shape = RoundedCornerShape(6.dp)
    val join = meeting.join
    val span = "${meeting.start.at(time, zone)}–${meeting.end.at(time, zone)}"

    Box(
        modifier
            .alpha(if (item.past) PAST_ALPHA else 1f)
            .clip(shape)
            .background(color.copy(alpha = if (meeting.tentative) TENTATIVE_WASH else BOX_WASH))
            .then(
                if (meeting.tentative) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = color,
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style =
                                Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                                ),
                        )
                    }
                } else if (item.current) {
                    Modifier.border(CURRENT_EDGE, color, shape)
                } else {
                    Modifier
                },
            ).combinedClickable(
                // Without a link to join, a tap shows what a hold would.
                onClick = {
                    details = join == null
                    join?.let { runCatching { uri.openUri(it) } }
                },
                onClickLabel = join?.let { stringResource(R.string.today_join) },
                onLongClick = { details = true },
            ),
    ) {
        Bar(color)
        BoxText(
            title = meeting.title,
            span = span,
            line = line,
            accent = color,
            ink = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = BAR_WIDTH),
        )
        DropdownMenu(expanded = details, onDismissRequest = { details = false }) {
            Column(
                Modifier.widthIn(max = DETAILS_WIDTH).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(meeting.title, style = MaterialTheme.typography.titleSmall.inScript())
                Text(span, style = MaterialTheme.typography.labelMedium, color = color)
                if (meeting.tentative) {
                    Text(stringResource(R.string.today_maybe), style = MaterialTheme.typography.labelMedium.inScript())
                }
                if (meeting.calendars.isNotEmpty()) {
                    Text(
                        stringResource(R.string.today_timeline_calendars, meeting.calendars.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall.inScript(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                meeting.overlaps?.let {
                    Text(
                        stringResource(R.string.today_overlaps, it),
                        style = MaterialTheme.typography.bodySmall.inScript(),
                        color = Accents.red,
                    )
                }
                if (join != null) {
                    TextButton(
                        onClick = {
                            details = false
                            runCatching { uri.openUri(join) }
                        },
                    ) {
                        LucideGlyph("video", size = 16.dp)
                        Text(stringResource(R.string.today_join), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/** A task: the accent colour, a box to tick it, and a tap that opens its note. */
@Composable
private fun TaskBox(
    item: TimelineItem,
    row: TaskRow,
    onComplete: (() -> Unit)?,
    onOpen: () -> Unit,
    modifier: Modifier,
    line: Dp,
    time: DateTimeFormatter,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(6.dp)
    val inProgress = row.state == IN_PROGRESS_STATE
    Box(
        modifier
            .alpha(if (item.past) PAST_ALPHA else 1f)
            .clip(shape)
            .background(accent.copy(alpha = BOX_WASH))
            .then(if (item.current) Modifier.border(CURRENT_EDGE, accent, shape) else Modifier)
            .clickable(onClick = onOpen),
    ) {
        Bar(accent)
        Row(Modifier.padding(start = BAR_WIDTH)) {
            LucideGlyph(
                name = if (inProgress) "square-dot" else "square",
                size = TICK,
                tint = accent,
                contentDescription =
                    stringResource(if (inProgress) R.string.task_state_in_progress else R.string.task_state_open),
                modifier =
                    Modifier
                        .padding(start = BOX_PADDING, top = (line + BOX_PADDING * 2 - TICK) / 2)
                        .then(
                            onComplete?.let { complete ->
                                Modifier
                                    .semantics { toggleableState = ToggleableState.Off }
                                    .clickable(
                                        onClickLabel = stringResource(R.string.task_complete_action),
                                        role = Role.Checkbox,
                                        onClick = complete,
                                    ).testTag("timeline-tick-${row.id}")
                            } ?: Modifier,
                        ),
            )
            val span = "${clockOf(item.start).format(time)}–${clockOf(item.end).format(time)}"
            BoxText(item.title, span, line, accent, MaterialTheme.colorScheme.onSurface, Modifier)
        }
    }
}

/** The coloured edge on a box's leading side. */
@Composable
private fun Bar(color: Color) {
    Box(Modifier.width(BAR_WIDTH).fillMaxHeight().background(color))
}

/**
 * What a box says: its title, and its time under it when the box holds two
 * lines; one line holds both.
 */
@Composable
private fun BoxText(
    title: String,
    span: String,
    line: Dp,
    accent: Color,
    ink: Color,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.padding(horizontal = BOX_PADDING, vertical = BOX_PADDING)) {
        val lines = (maxHeight / line).toInt().coerceAtLeast(1)
        val style = MaterialTheme.typography.labelMedium.inScript()
        if (lines < 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(span, style = style, color = accent, maxLines = 1)
                Text(
                    title,
                    style = style,
                    color = ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Column {
                Text(
                    title,
                    style = style,
                    color = ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = lines - 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(span, style = style, color = accent, maxLines = 1)
            }
        }
    }
}

@Composable
private fun lineHeight(style: TextStyle): Dp {
    val height = if (style.lineHeight.isSp) style.lineHeight else style.fontSize.takeIf { it.isSp } ?: 16.sp
    return with(LocalDensity.current) { height.toDp() }
}

/** A minute of the day as a time; 24:00 is the midnight that ends it. */
private fun clockOf(minute: Int): LocalTime =
    if (minute >= DAY) LocalTime.MIDNIGHT else LocalTime.of(minute / HOUR, minute % HOUR)

private fun Long?.at(
    time: DateTimeFormatter,
    zone: ZoneId,
): String = this?.let { Instant.ofEpochMilli(it).atZone(zone).format(time) }.orEmpty()

private const val MINUTE_MS = 60_000L
private const val HOUR = 60
private const val DAY = 1440
private const val IN_PROGRESS_STATE = "IN_PROGRESS"
private const val BOX_WASH = 0.18f
private const val TENTATIVE_WASH = 0.06f
private const val FREE_WASH = 0.10f
private const val PAST_ALPHA = 0.45f

/** A minute on the axis: 1 dp, so an hour is 60 dp. */
private val MINUTE = 1.dp
private val GUTTER = 52.dp
private val LANE_GAP = 2.dp
private val BOX_PADDING = 2.dp
private val BAR_WIDTH = 3.dp
private val HAIRLINE = 0.5.dp
private val NOW_LINE = 2.dp
private val NOW_DOT = 8.dp
private val CURRENT_EDGE = 1.5.dp
private val TICK = 14.dp
private val DETAILS_WIDTH = 280.dp
