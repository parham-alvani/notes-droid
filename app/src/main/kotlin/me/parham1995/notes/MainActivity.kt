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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import me.parham1995.notes.data.TaskDigestWorker
import me.parham1995.notes.navigation.NotesNavHost
import me.parham1995.notes.ui.icon.ProvideLucide
import me.parham1995.notes.ui.theme.NotesTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Set when the digest notification launched us, and again on a new intent
     * because the activity is singleTop -- tapping the notification while the
     * app is already open delivers here rather than starting it again.
     */
    private val openTasks = MutableStateFlow(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(TaskDigestWorker.EXTRA_OPEN_TASKS, false)) openTasks.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTasks.value = intent?.getBooleanExtra(TaskDigestWorker.EXTRA_OPEN_TASKS, false) == true
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
                    NotesNavHost(openTasks = openTasks)
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
}
