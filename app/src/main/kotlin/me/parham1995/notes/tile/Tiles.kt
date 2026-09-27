package me.parham1995.notes.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.parham1995.notes.MainActivity
import me.parham1995.notes.feature.capture.CaptureActivity
import me.parham1995.notes.navigation.EXTRA_OPEN
import me.parham1995.notes.navigation.SCREEN_TODAY

/**
 * A Quick Settings tile that opens one thing, from anywhere, in one swipe and
 * a tap -- the launcher shortcuts, for when the launcher is not what is on
 * screen.
 *
 * A tile is not a toggle here, so it always draws as inactive: an "on" state
 * would claim something is running that is not. On a locked phone the tap
 * asks for the unlock first, because what it opens is the vault.
 */
abstract class LaunchTile : TileService() {
    protected abstract fun target(): Intent

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { launch() } else launch()
    }

    // The Intent form is what API 33 and below have; 34 wants a PendingIntent.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch() {
        val intent = target().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    javaClass.name.hashCode(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** A line into the scratchpad, without opening the vault first. */
class CaptureTile : LaunchTile() {
    override fun target() = Intent(this, CaptureActivity::class.java).setAction(Intent.ACTION_VIEW)
}

/** This week's (or today's) journal note, as the launcher shortcut opens it. */
class TodayTile : LaunchTile() {
    override fun target() =
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(EXTRA_OPEN, SCREEN_TODAY)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
