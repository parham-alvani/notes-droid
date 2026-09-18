package me.parham1995.notes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color.TRANSPARENT
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import me.parham1995.notes.data.TaskDigestWorker
import me.parham1995.notes.navigation.NotesNavHost
import me.parham1995.notes.ui.icon.ProvideLucide
import me.parham1995.notes.ui.theme.NotesTheme
import me.parham1995.notes.widget.RecentNotesWidget

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Which screen something outside the app asked for: the digest
     * notification, a launcher shortcut, the widget.
     *
     * Re-read on a new intent as well as on create, because the activity is
     * singleTop -- tapping a shortcut while the app is already open delivers
     * here rather than starting it again.
     */
    private val openScreen = MutableStateFlow<String?>(null)

    /** A note id from a widget row. */
    private val openNote = MutableStateFlow<Long?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openScreen.value = requestedScreen(intent)
        openNote.value = requestedNote(intent)
    }

    private fun requestedNote(intent: Intent?): Long? =
        intent?.getLongExtra(RecentNotesWidget.EXTRA_NOTE, 0L)?.takeIf { it > 0 }

    /** The shortcut's string form, or the notification's older boolean. */
    private fun requestedScreen(intent: Intent?): String? =
        when {
            intent == null -> null
            intent.getStringExtra(EXTRA_OPEN) != null -> intent.getStringExtra(EXTRA_OPEN)
            intent.getBooleanExtra(TaskDigestWorker.EXTRA_OPEN_TASKS, false) -> SCREEN_TASKS
            else -> null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super, which is where the library hooks the window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        openScreen.value = requestedScreen(intent)
        // naz is a dark colorscheme, so the system bars take light icons
        // regardless of what the device is set to.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        askForNotifications()
        setContent {
            NotesTheme {
                ProvideLucide {
                    NotesNavHost(openScreen = openScreen, openNote = openNote)
                }
            }
        }
    }

    /**
     * Sync runs as a foreground worker, which on API 33+ cannot post its
     * progress notification without this. Without the permission the first
     * sync -- the long one -- has no way to survive the app going to the
     * background.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        /** Matches `me.parham1995.notes.OPEN` in `res/xml/shortcuts.xml`. */
        const val EXTRA_OPEN = "me.parham1995.notes.OPEN"
        const val SCREEN_TASKS = "tasks"
    }
}
