package me.parham1995.notes.tile

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.database.VaultEntity
import org.junit.Test

class SyncTileStatusTest {
    private fun vault(
        name: String,
        at: Long? = 1_000,
        error: String? = null,
        enabled: Boolean = true,
    ) = VaultEntity(owner = "o", repo = name, name = name, lastSyncAt = at, lastError = error, enabled = enabled)

    @Test
    fun `the oldest sync is how out of date the phone is`() {
        val status =
            SyncTileStatus.of(
                listOf(vault("notes", at = 5_000), vault("archive", at = 2_000)),
                running = false,
            )

        assertThat(status).isEqualTo(SyncTileStatus.Synced(2_000))
    }

    @Test
    fun `a failure outranks a recent sync, and is named`() {
        val status =
            SyncTileStatus.of(
                listOf(vault("notes", at = 9_000), vault("archive", error = "Auth failed")),
                running = false,
            )

        assertThat(status).isEqualTo(SyncTileStatus.Failed(listOf("archive")))
    }

    @Test
    fun `running outranks everything the last sync said`() {
        val status = SyncTileStatus.of(listOf(vault("notes", error = "boom")), running = true)

        assertThat(status).isEqualTo(SyncTileStatus.Running)
    }

    @Test
    fun `a vault switched off is not counted`() {
        assertThat(
            SyncTileStatus.of(listOf(vault("off", error = "boom", enabled = false), vault("notes")), running = false),
        ).isEqualTo(SyncTileStatus.Synced(1_000))
        assertThat(SyncTileStatus.of(listOf(vault("off", enabled = false)), running = false))
            .isEqualTo(SyncTileStatus.NoVaults)
    }

    @Test
    fun `one vault never synced means the phone is not up to date`() {
        assertThat(SyncTileStatus.of(listOf(vault("notes"), vault("new", at = null)), running = false))
            .isEqualTo(SyncTileStatus.Never)
    }
}
