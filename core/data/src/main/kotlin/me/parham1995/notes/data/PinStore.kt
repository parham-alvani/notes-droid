package me.parham1995.notes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A note pinned to the home screen: where it lives, not which row it is.
 *
 * A note id is a row number, and a vault removed and added again reissues all
 * of them. A vault and a path are what the note is, so a pin survives a
 * reindex, a resync and an upgrade, and is resolved to a row only when it is
 * drawn.
 */
data class Pin(
    val vaultId: Long,
    val path: String,
)

/**
 * The notes pinned to the home screen, newest first.
 *
 * Device-local and never written to the vault: pinning is about this phone's
 * home screen, which is nothing the desktop needs to know, and the app only
 * writes the three edits it has promised to.
 *
 * Kept in the settings DataStore rather than a table, because a list of a
 * dozen paths is not worth a migration, and because a pin outliving a wiped
 * database is exactly right -- the next sync brings the note back, and the
 * pin finds it again.
 */
@Singleton
class PinStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Every pin in every vault, the most recently pinned first. */
        val pins: Flow<List<Pin>> =
            context.settingsDataStore.data
                .map { PinCodec.decode(it[PINS].orEmpty()) }
                .distinctUntilChanged()

        /** The pins of one vault. Another vault's `README.md` is another note. */
        fun inVault(vaultId: Long): Flow<List<Pin>> =
            pins.map { all -> all.filter { it.vaultId == vaultId } }.distinctUntilChanged()

        fun isPinned(
            vaultId: Long,
            path: String,
        ): Flow<Boolean> = pins.map { all -> Pin(vaultId, path) in all }.distinctUntilChanged()

        /** Pins a note, or moves it to the front if it already was. */
        suspend fun pin(
            vaultId: Long,
            path: String,
        ) = update { all ->
            val pin = Pin(vaultId, path)
            listOf(pin) + all.filterNot { it == pin }
        }

        suspend fun unpin(
            vaultId: Long,
            path: String,
        ) = update { all -> all.filterNot { it == Pin(vaultId, path) } }

        /** Pins it if it was not, unpins it if it was; says which it is now. */
        suspend fun toggle(
            vaultId: Long,
            path: String,
        ): Boolean {
            var pinned = false
            update { all ->
                val pin = Pin(vaultId, path)
                if (pin in all) {
                    all.filterNot { it == pin }
                } else {
                    pinned = true
                    listOf(pin) + all
                }
            }
            return pinned
        }

        /** Drops pins whose notes are gone. */
        suspend fun forget(gone: Collection<Pin>) {
            if (gone.isEmpty()) return
            val set = gone.toSet()
            update { all -> all.filterNot { it in set } }
        }

        private suspend fun update(change: (List<Pin>) -> List<Pin>) {
            context.settingsDataStore.edit { preferences ->
                val next = change(PinCodec.decode(preferences[PINS].orEmpty()))
                if (next.isEmpty()) preferences.remove(PINS) else preferences[PINS] = PinCodec.encode(next)
            }
        }

        private companion object {
            val PINS = stringPreferencesKey("pinned_notes")
        }
    }

/**
 * Pins as text: one per line, the vault id, a tab, then the path.
 *
 * The path goes last and is split off at the first tab, so a path is read back
 * exactly as written whatever it contains short of a line break -- which no
 * note's path does. A line that does not parse is dropped rather than failing
 * the whole list.
 */
internal object PinCodec {
    fun encode(pins: List<Pin>): String =
        pins
            .filterNot { '\n' in it.path || it.path.isEmpty() }
            .distinct()
            .joinToString("\n") { "${it.vaultId}\t${it.path}" }

    fun decode(raw: String): List<Pin> =
        raw
            .lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val vaultId = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
                val path = line.substring(tab + 1)
                if (path.isEmpty()) null else Pin(vaultId, path)
            }.distinct()
            .toList()
}
