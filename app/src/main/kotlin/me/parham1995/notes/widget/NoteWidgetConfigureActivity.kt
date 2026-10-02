package me.parham1995.notes.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.ui.theme.NotesTheme
import javax.inject.Inject

/**
 * Which note a note widget shows: the notes read lately, or any note by name.
 *
 * Reached from the launcher when the widget is placed or reconfigured, and
 * from the widget itself while it has no note. Only a widget of this app's own
 * is accepted -- the activity is exported, as the launcher needs, so the id it
 * is handed is checked rather than trusted.
 */
@AndroidEntryPoint
class NoteWidgetConfigureActivity : ComponentActivity() {
    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out of a first placement removes the widget rather than
        // leaving an empty one behind.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (provider != ComponentName(this, NoteWidget::class.java)) {
            finish()
            return
        }
        enableEdgeToEdge()
        val theme = settingsStore.settings.map { it.reading.theme }
        setContent {
            val chosen by theme.collectAsStateWithLifecycle(initialValue = null)
            NotesTheme(theme = chosen ?: return@setContent) {
                NotePicker(repository) { note -> bind(widgetId, note) }
            }
        }
    }

    private fun bind(
        widgetId: Int,
        note: NoteEntity,
    ) {
        NoteWidgetBindings(this)[widgetId] = Pin(note.vaultId, note.path)
        // The redraw is asked for before the result is given, so the launcher
        // never shows the new widget empty. Asking only enqueues Glance's
        // session; the composing happens in its worker.
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(widgetId)
            NoteAppWidget().update(applicationContext, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotePicker(
    repository: VaultRepository,
    onPick: (NoteEntity) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val recent by repository.recentlyOpened(RECENT).collectAsStateWithLifecycle(initialValue = emptyList())
    var found by remember { mutableStateOf(emptyList<NoteEntity>()) }
    LaunchedEffect(query) { found = repository.quickSwitch(query) }
    val shown = if (query.isBlank()) recent else found

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_note_pick)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.widget_note_search)) },
                singleLine = true,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { note ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(note) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(note.title.ifBlank { note.name }, style = MaterialTheme.typography.bodyLarge)
                        if (note.parent.isNotEmpty()) {
                            Text(
                                note.parent,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val RECENT = 30
