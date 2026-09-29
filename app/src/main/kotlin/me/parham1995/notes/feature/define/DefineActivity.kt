package me.parham1995.notes.feature.define

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.ui.icon.ProvideLucide
import me.parham1995.notes.ui.theme.NotesTheme
import javax.inject.Inject

/**
 * An English dictionary, one tap from any selected word.
 *
 * Reached through the text-selection menu as "Define" -- in a note, and just
 * as much in a browser or a chat -- and from a launcher shortcut with nothing
 * selected, to type a word in. A sheet over whatever was on screen, like
 * capture: looking a word up should not mean leaving what was being read.
 *
 * Everything is on the phone. No word leaves it, and it works with no signal.
 */
@AndroidEntryPoint
class DefineActivity : ComponentActivity() {
    @Inject
    lateinit var settingsStore: SettingsStore

    private val viewModel: DefineViewModel by viewModels()

    private val themes: Flow<ThemeChoice?> by lazy {
        settingsStore.settings.map<VaultSettings, ThemeChoice?> { it.reading.theme }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Behind the system bars, so the sheet reaches the bottom of the
        // screen. Dark bars as in the reader: left to follow the system, a
        // phone in light mode put a white band under the sheet.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A recreation keeps the view model and what it found; the selection
        // it was started with has already been looked up.
        if (savedInstanceState == null) selected(intent)?.let(viewModel::lookUp)

        setContent {
            val theme by themes.collectAsStateWithLifecycle(initialValue = null)
            val chosen = theme ?: return@setContent
            NotesTheme(theme = chosen) {
                ProvideLucide {
                    DefineSheet(viewModel = viewModel, onDismiss = ::finish)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selected(intent)?.let(viewModel::lookUp)
    }

    companion object {
        /** The dictionary open on [word], as the selection menu would open it. */
        fun lookUp(
            context: Context,
            word: String,
        ): Intent =
            Intent(context, DefineActivity::class.java)
                .setAction(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }

    private fun selected(intent: Intent?): String? =
        intent
            ?.takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
}
