package me.parham1995.notes.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.icon.LucideGlyph

/** The phone's own way of proving who is holding it: a fingerprint, a face, or the screen lock. */
object Unlocker {
    /**
     * Whether the phone has a screen lock at all. Without one there is nothing
     * to fall back to when a finger will not read, and a lock that cannot be
     * opened is a vault that cannot be read.
     */
    fun canLock(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun ask(
        activity: Activity,
        title: String,
        onSuccess: () -> Unit,
    ) {
        val builder = BiometricPrompt.Builder(activity).setTitle(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
        } else {
            // The only way API 29 has to offer the screen lock beside the finger.
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
    }
}

/**
 * What covers the app while it is locked.
 *
 * Drawn over the screens rather than instead of them, so unlocking returns to
 * exactly the note and the place in it that was being read -- and a link or a
 * widget tapped while locked has already opened its note underneath. The
 * surface takes every touch, and back leaves the app rather than reaching the
 * page below.
 */
@Composable
fun LockScreen(
    onUnlock: () -> Unit,
    onLeave: () -> Unit,
) {
    // Asked straight away: the lock screen is only ever a way to that prompt.
    LaunchedEffect(Unit) { onUnlock() }
    BackHandler(onBack = onLeave)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LucideGlyph("lock-keyhole", size = 48.dp, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}
