package me.parham1995.notes.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.VaultEntity
import javax.inject.Inject

/** What the sync tile says, worked out from the vaults and the work queue. */
sealed interface SyncTileStatus {
    data object Running : SyncTileStatus

    /** No vault is set up, or none is switched on. */
    data object NoVaults : SyncTileStatus

    /** At least one vault has never finished a sync. */
    data object Never : SyncTileStatus

    /** The vaults whose last sync failed, by name. */
    data class Failed(
        val vaults: List<String>,
    ) : SyncTileStatus

    /**
     * Every vault synced; [at] is the *oldest* of their last syncs, because
     * that is how out of date the vault on the phone can be.
     */
    data class Synced(
        val at: Long,
    ) : SyncTileStatus

    companion object {
        /**
         * Failure outranks age: a vault that failed an hour ago and one that
         * synced a minute ago are not "synced a minute ago". Running outranks
         * both, because whatever the last sync said is about to change.
         */
        fun of(
            vaults: List<VaultEntity>,
            running: Boolean,
        ): SyncTileStatus {
            if (running) return Running
            val enabled = vaults.filter { it.enabled }
            if (enabled.isEmpty()) return NoVaults
            val failed = enabled.filter { it.lastError != null }
            if (failed.isNotEmpty()) return Failed(failed.map { it.label })
            val times = enabled.map { it.lastSyncAt }
            if (times.any { it == null }) return Never
            return Synced(times.filterNotNull().min())
        }
    }
}

/**
 * Whether the vaults are up to date, from the notification shade, and a tap
 * to sync them now.
 *
 * Active while a sync runs -- the one thing this tile can truthfully be "on"
 * for -- and inactive otherwise. The subtitle says when the vaults last synced
 * or which one failed; the reason is in Settings, under the sync journal. On
 * a locked phone a failure is counted rather than named, as the app lock
 * hides the vault everywhere else.
 */
@AndroidEntryPoint
class SyncTile : TileService() {
    @Inject lateinit var vaults: VaultRepository

    @Inject lateinit var scheduler: SyncScheduler

    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listening = scope
        scope.launch {
            combine(vaults.vaults(), scheduler.running()) { all, running -> SyncTileStatus.of(all, running) }
                .distinctUntilChanged()
                .collect(::draw)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    /** Syncing reveals nothing, so a locked phone need not be unlocked first. */
    override fun onClick() {
        scheduler.syncNow()
    }

    private fun draw(status: SyncTileStatus) {
        val tile = qsTile ?: return
        tile.state = if (status == SyncTileStatus.Running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle =
            when (status) {
                SyncTileStatus.Running -> getString(R.string.tile_sync_running)
                SyncTileStatus.NoVaults -> getString(R.string.tile_sync_no_vaults)
                SyncTileStatus.Never -> getString(R.string.tile_sync_never)
                is SyncTileStatus.Failed ->
                    if (isLocked || status.vaults.size > 1) {
                        resources.getQuantityString(
                            R.plurals.tile_sync_failed_count,
                            status.vaults.size,
                            status.vaults.size,
                        )
                    } else {
                        getString(R.string.tile_sync_failed, status.vaults.single())
                    }
                is SyncTileStatus.Synced -> {
                    val now = System.currentTimeMillis()
                    // "0 min. ago" reads as a fault; under a minute is just now.
                    if (now - status.at < DateUtils.MINUTE_IN_MILLIS) {
                        getString(R.string.tile_sync_just_now)
                    } else {
                        DateUtils
                            .getRelativeTimeSpanString(
                                status.at,
                                now,
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE,
                            ).toString()
                    }
                }
            }
        tile.updateTile()
    }
}
