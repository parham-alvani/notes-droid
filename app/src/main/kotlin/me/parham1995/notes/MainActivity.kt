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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.navigation.NotesNavHost
import me.parham1995.notes.navigation.consumeLaunchRequest
import me.parham1995.notes.navigation.launchRequest
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.ProvideLucide
import me.parham1995.notes.ui.theme.NotesTheme
import javax.inject.Inject
import javax.inject.Provider

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

    @Inject
    lateinit var settingsStore: Provider<SettingsStore>

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        answer(intent, restoring = false)
    }

    /**
     * Acts on what [intent] asks for, once.
     *
     * The request is taken off the intent as it is read: the activity keeps
     * that intent across every recreation, and one that still carried it would
     * ask again.
     */
    private fun answer(
        intent: Intent?,
        restoring: Boolean,
    ) {
        val request = launchRequest(intent, restoring)
        intent?.consumeLaunchRequest()
        openScreen.value = request?.screen
        openNote.value = request?.note
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super, which is where the library hooks the window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // A widget row on a cold start arrives here, not in onNewIntent. Only
        // a first creation acts on it; see launchRequest.
        answer(intent, restoring = savedInstanceState != null)
        // naz is a dark colorscheme, so the system bars take light icons
        // regardless of what the device is set to.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        askForNotifications()
        setContent {
            // Read here rather than in each screen: the theme and the reading
            // settings apply to everything below, and a screen that had to ask
            // for them would be a screen that could forget to.
            val settings by settingsStore
                .get()
                .settings
                .collectAsStateWithLifecycle(initialValue = VaultSettings())

            NotesTheme(theme = settings.reading.theme) {
                CompositionLocalProvider(LocalReading provides settings.reading) {
                    ProvideLucide {
                        NotesNavHost(
                            openScreen = openScreen,
                            openNote = openNote,
                            startScreen = settings.reading.startScreen,
                        )
                    }
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
