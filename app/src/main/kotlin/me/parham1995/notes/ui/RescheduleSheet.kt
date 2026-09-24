package me.parham1995.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * The days a task can be pushed to without picking one.
 *
 * Always strictly after [today]: on a Saturday "this weekend" is next
 * Saturday, because pushing a task to the day it is already late on is not
 * pushing it anywhere.
 */
data class RescheduleChoices(
    val tomorrow: LocalDate,
    val weekend: LocalDate,
    val nextWeek: LocalDate,
) {
    companion object {
        fun from(today: LocalDate) =
            RescheduleChoices(
                tomorrow = today.plusDays(1),
                weekend = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)),
                nextWeek = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
            )
    }
}

/**
 * Where to push a task: tomorrow, the weekend, next week, or a date.
 *
 * Three fixed answers first because they are nearly every answer -- triage of
 * a long overdue list is the same few choices made fifty times -- and the
 * calendar only behind a fourth, for the task that has an actual day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescheduleSheet(
    today: LocalDate,
    onChoose: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = remember(today) { RescheduleChoices.from(today) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    choices.tomorrow
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { millis ->
                            // The picker speaks UTC midnight, whatever the
                            // device's zone, so it is read back in UTC too.
                            onChoose(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                    },
                    enabled = picker.selectedDateMillis != null,
                ) { Text(stringResource(R.string.reschedule_move)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state = picker)
        }
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.reschedule_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Choice(stringResource(R.string.reschedule_tomorrow), choices.tomorrow) { onChoose(choices.tomorrow) }
            Choice(stringResource(R.string.reschedule_weekend), choices.weekend) { onChoose(choices.weekend) }
            Choice(stringResource(R.string.reschedule_next_week), choices.nextWeek) { onChoose(choices.nextWeek) }
            Choice(stringResource(R.string.reschedule_pick), date = null) { picking = true }
        }
    }
}

@Composable
private fun Choice(
    label: String,
    date: LocalDate?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent =
            date?.let {
                {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
