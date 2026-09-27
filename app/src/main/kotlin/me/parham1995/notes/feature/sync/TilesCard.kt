package me.parham1995.notes.feature.sync

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.tile.CaptureTile
import me.parham1995.notes.tile.TodayTile

/**
 * The Quick Settings tiles, offered where someone would look for them.
 *
 * Launchers differ in how -- and whether -- an app's tiles can be found in the
 * panel's own editor; One UI's hides them several screens deep. Android 13
 * lets an app ask, with the system's own confirmation, so the buttons do
 * that. Before 13 there is only the editor, and the card says so.
 */
@Composable
internal fun TilesCard() {
    val context = LocalContext.current
    SectionCard(stringResource(R.string.card_tiles)) {
        Text(
            stringResource(
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    R.string.help_tiles
                } else {
                    R.string.help_tiles_editor
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ask(context, CaptureTile::class.java, R.string.shortcut_capture_short, R.drawable.ic_tile_capture)
                }) { Text(stringResource(R.string.shortcut_capture_short)) }
                OutlinedButton(onClick = {
                    ask(context, TodayTile::class.java, R.string.shortcut_today_short, R.drawable.ic_tile_today)
                }) { Text(stringResource(R.string.shortcut_today_short)) }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun ask(
    context: Context,
    tile: Class<*>,
    @StringRes label: Int,
    @DrawableRes icon: Int,
) {
    // Added or declined, the system's own dialog has said so. Already there,
    // it says nothing at all, and a button that does nothing looks broken.
    context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
        ComponentName(context, tile),
        context.getString(label),
        Icon.createWithResource(context, icon),
        context.mainExecutor,
    ) { result ->
        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
            Toast.makeText(context, context.getString(R.string.tile_already_added), Toast.LENGTH_SHORT).show()
        }
    }
}
