package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.markdown.NoteExcerpt
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A pin found again: the note it names, and the first lines of it. */
data class PinnedNote(
    val noteId: Long,
    val vaultId: Long,
    val path: String,
    val title: String,
    /** The opening lines as plain text, one per line. */
    val excerpt: String,
)

/**
 * Pins, resolved to the notes they name.
 *
 * A pin is a vault and a path, and those are only a note while the note is
 * there: renamed on the desktop or deleted, it is a pin to nothing. Such a pin
 * is left out of what is drawn and forgotten -- but only once the vault has
 * been indexed at all, since a vault not yet synced has no notes to find and
 * every pin in it would otherwise be thrown away before it had a chance.
 */
@Singleton
class PinnedNotes
    @Inject
    constructor(
        private val pins: PinStore,
        private val notes: NoteDao,
        private val files: VaultFileStore,
    ) {
        /**
         * Up to [limit] pinned notes of [vaultId], newest pin first, each with
         * the first [lines] lines of its text.
         *
         * Reads at most [EXCERPT_BYTES] of each file. This runs inside a
         * widget's broadcast, which has seconds, and a pinned note can be the
         * 139KB one; eight lines never need more than the top of it.
         */
        suspend fun resolve(
            vaultId: Long,
            limit: Int,
            lines: Int = NoteExcerpt.DEFAULT_LINES,
        ): List<PinnedNote> {
            val wanted = pins.inVault(vaultId).first()
            if (wanted.isEmpty() || limit <= 0) return emptyList()

            val gone = ArrayList<Pin>()
            val found = ArrayList<PinnedNote>()
            for (pin in wanted) {
                val note = notes.byPath(pin.vaultId, pin.path)
                if (note == null) {
                    gone.add(pin)
                    continue
                }
                if (found.size >= limit) continue
                val title = note.title.ifBlank { note.name }
                val text =
                    runCatchingUnlessCancelled { readHead(files.fileFor(note.vaultId, note.path), EXCERPT_BYTES) }
                        .getOrNull()
                val excerpt =
                    text?.let { runCatchingUnlessCancelled { NoteExcerpt.text(it, lines, title) }.getOrNull() }
                found.add(PinnedNote(note.id, note.vaultId, note.path, title, excerpt.orEmpty()))
            }

            if (gone.isNotEmpty() && notes.count(vaultId).first() > 0) pins.forget(gone)
            return found
        }

        companion object {
            /** Enough for eight lines of anything short of a wall of front matter. */
            const val EXCERPT_BYTES = 16 * 1024

            /**
             * The start of [file], at most [maxBytes] of it, as text.
             *
             * When the file is longer the last line is dropped, because it was
             * cut part way through -- possibly part way through a character,
             * which would otherwise end the card in a replacement glyph.
             */
            internal suspend fun readHead(
                file: File,
                maxBytes: Int,
            ): String? =
                withContext(Dispatchers.IO) {
                    if (!file.isFile) return@withContext null
                    val buffer = ByteArray(maxBytes)
                    var read = 0
                    var more = false
                    file.inputStream().use { input ->
                        while (read < maxBytes) {
                            val n = input.read(buffer, read, maxBytes - read)
                            if (n < 0) break
                            read += n
                        }
                        more = read == maxBytes && input.read() >= 0
                    }
                    headText(buffer, read, more)
                }

            internal fun headText(
                bytes: ByteArray,
                length: Int,
                truncated: Boolean,
            ): String {
                if (!truncated) return bytes.decodeToString(0, length)
                var end = length
                while (end > 0 && bytes[end - 1] != '\n'.code.toByte()) end--
                // One line longer than the whole budget: keep it, minus the
                // character the cut went through.
                if (end == 0) return bytes.decodeToString(0, length).trimEnd('\uFFFD')
                return bytes.decodeToString(0, end)
            }
        }
    }
