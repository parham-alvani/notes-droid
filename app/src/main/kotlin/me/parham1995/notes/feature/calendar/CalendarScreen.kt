package me.parham1995.notes.feature.calendar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.data.calendar.PhoneEvent
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.ui.TaskReasonLine
import me.parham1995.notes.ui.icon.LucideGlyph
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onOpenNote: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { viewModel.permissionAnswered() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.calendar_title)) }) }) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when {
            state.loading -> Unit
            !state.permitted ->
                Prompt(stringResource(R.string.calendar_needs_access), modifier) {
                    Button(onClick = { permission.launch(Manifest.permission.READ_CALENDAR) }) {
                        Text(stringResource(R.string.calendar_allow))
                    }
                }
            state.unchosen ->
                Prompt(stringResource(R.string.calendar_choose), modifier) {
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.calendar_open_settings)) }
                }
            else ->
                Agenda(
                    days = state.days,
                    today = LocalDate.now(),
                    onOpenEvent = { event ->
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
                        val intent =
                            Intent(Intent.ACTION_VIEW, uri)
                                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.occurrence.begin)
                                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.occurrence.end)
                        // A phone with no calendar app has nothing to open it in.
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                        }
                    },
                    onOpenTask = { onOpenNote(it.noteId) },
                    modifier = modifier,
                )
        }
    }
}

@Composable
private fun Prompt(
    text: String,
    modifier: Modifier,
    action: @Composable () -> Unit,
) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

/**
 * The days, each under a header that stays while its day scrolls. Apart from
 * the screen so it can be drawn in a test with days the test made.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Agenda(
    days: List<AgendaDay>,
    today: LocalDate,
    onOpenEvent: (PhoneEvent) -> Unit,
    onOpenTask: (TaskRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        days.forEach { day ->
            stickyHeader(key = "day-${day.date}") { DayHeader(day.date, today) }
            if (day.isEmpty) {
                item(key = "free-${day.date}") {
                    Text(
                        stringResource(R.string.calendar_free),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(day.events, key = { "event-${day.date}-${it.eventId}-${it.occurrence.begin}" }) { event ->
                EventRow(event, day.date) { onOpenEvent(event) }
            }
            items(day.tasks, key = { "task-${day.date}-${it.id}" }) { task ->
                TaskRowView(task) { onOpenTask(task) }
            }
            item(key = "end-${day.date}") { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) }
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate,
    today: LocalDate,
) {
    val name =
        when (date) {
            today -> stringResource(R.string.calendar_today)
            today.plusDays(1) -> stringResource(R.string.calendar_tomorrow)
            else -> null
        }
    val full = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    // Opaque: it scrolls over the rows beneath it.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name ?: full,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (name != null) {
                Text(
                    full,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    event: PhoneEvent,
    day: LocalDate,
    onClick: () -> Unit,
) {
    val occurrence = event.occurrence
    val time =
        if (occurrence.allDay) {
            stringResource(R.string.calendar_all_day)
        } else {
            val zone = ZoneId.systemDefault()
            val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            val start = Instant.ofEpochMilli(occurrence.begin).atZone(zone)
            val end = Instant.ofEpochMilli(occurrence.end).atZone(zone)
            // Past midnight, the end is on another day; say which.
            val endText = if (end.toLocalDate() == day) end.format(format) else end.format(SHORT_DAY_TIME)
            "${start.format(format)} – $endText"
        }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(36.dp).background(Color(event.color), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(
                occurrence.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.calendar_no_title),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(time, occurrence.location?.takeIf { it.isNotBlank() }, occurrence.calendar)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TaskRowView(
    task: TaskRow,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideGlyph("square", size = 16.dp, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(task.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            task.reason?.let { TaskReasonLine(it, maxLines = 1) }
            Text(
                task.noteTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private val SHORT_DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm")
