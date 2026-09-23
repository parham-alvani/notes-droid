package me.parham1995.notes.feature.sync

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.BuildConfig
import me.parham1995.notes.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shown only after a crash, and only until it is dismissed.
 *
 * There is no store console behind this app and logcat is gone by the time
 * anyone thinks to look, so the alternative to putting it here is losing the
 * reason entirely.
 */
@Composable
internal fun CrashCard(
    crash: String,
    viewModel: SyncViewModel,
) {
    val context = LocalContext.current
    val crashSubject = stringResource(R.string.settings_crash_subject)
    val shareTitle = stringResource(R.string.crash_share_title)
    SectionCard(stringResource(R.string.card_crash)) {
        Text(
            crash.lineSequence().take(CRASH_PREVIEW_LINES).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = CRASH_PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, crashSubject)
                                putExtra(Intent.EXTRA_TEXT, crash)
                            },
                            shareTitle,
                        ),
                    )
                }
            }) {
                Text(stringResource(R.string.action_share))
            }
            OutlinedButton(onClick = viewModel::dismissCrash) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

/**
 * What this build actually is.
 *
 * Useful when more than one APK is in circulation -- a release from the tag, a
 * build handed over directly -- and the only way to tell them apart used to be
 * to look at the file you installed from. The date is the commit's, not the
 * moment of the build, so two builds of the same source say the same thing.
 */
@Composable
internal fun AboutCard() {
    val context = LocalContext.current
    SectionCard(stringResource(R.string.card_about)) {
        LabelledValue(
            stringResource(R.string.about_version),
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        LabelledValue(stringResource(R.string.about_released), BuildConfig.BUILD_DATE)
        LabelledValue(stringResource(R.string.status_commit), BuildConfig.GIT_SHA)
        LabelledValue(stringResource(R.string.about_author), BuildConfig.AUTHOR)
        LabelledValue(stringResource(R.string.about_license), BuildConfig.LICENSE)
        // The bundled font is separately licensed and the OFL asks that it be
        // acknowledged wherever the software is.
        LabelledValue(stringResource(R.string.about_persian_type), "Vazirmatn, OFL 1.1")
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.REPOSITORY.toUri()))
                }
            },
        ) {
            Text(BuildConfig.REPOSITORY.removePrefix("https://"))
        }
    }
}

private const val CRASH_PREVIEW_LINES = 8

/**
 * The sync journal.
 *
 * A sync runs in the background and takes minutes on its first run, so "it did
 * not work" is otherwise all there is to go on. This shows what each step
 * actually did, on the device, without a cable.
 */
@Composable
internal fun LogCard(viewModel: SyncViewModel) {
    val entries by viewModel.log.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val stamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val context = LocalContext.current
    val logSubject = stringResource(R.string.settings_log_subject)
    val shareTitle = stringResource(R.string.log_share_title)

    SectionCard(stringResource(R.string.card_log)) {
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.journal_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        val shown = if (expanded) entries else entries.take(COLLAPSED_LOG_LINES)
        shown.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = time.format(Date(entry.at)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when (entry.level) {
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "WARN" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.size > COLLAPSED_LOG_LINES) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.log_show_less)
                        } else {
                            stringResource(R.string.log_show_all, entries.size)
                        },
                    )
                }
            }
            // Reading a log off a phone screen and retyping it is the reason a
            // journal goes unread. One tap sends the whole thing anywhere.
            TextButton(onClick = {
                val text =
                    entries.reversed().joinToString("\n") { entry ->
                        stamp.format(Date(entry.at)) + "  " + entry.level + "  " + entry.message
                    }
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, logSubject)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                context.startActivity(Intent.createChooser(send, shareTitle))
            }) {
                Text(stringResource(R.string.action_share))
            }
            TextButton(onClick = viewModel::clearLog) { Text(stringResource(R.string.action_clear)) }
        }
    }
}

private const val COLLAPSED_LOG_LINES = 12
