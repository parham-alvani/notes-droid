package me.parham1995.notes.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.BlobEntity
import me.parham1995.notes.data.database.PendingEditDao
import me.parham1995.notes.data.database.PendingEditEntity
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.markdown.TaskLine
import me.parham1995.notes.markdown.TaskNotFound
import me.parham1995.notes.markdown.VaultEdits
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState
import me.parham1995.notes.sync.TextEdit
import me.parham1995.notes.sync.WriteOutcome
import me.parham1995.notes.sync.gitBlobSha
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** The shapes of edit the app can make. One each for the [VaultEdits]. */
enum class EditKind {
    /** Find a line and replace it -- how a task is ticked. */
    REPLACE_LINE,

    /** Add a line at the end of a section, creating the section if need be. */
    ADD_UNDER,

    /** Put a block at the end of the file, verbatim. */
    APPEND,

    /**
     * Move a task to another day. The anchor is the task as the index read
     * it, and the text is its recorded line and the date -- or the line alone,
     * which takes off a scheduled date an earlier move added.
     */
    RESCHEDULE,
    ;

    companion object {
        fun parse(raw: String?): EditKind? = entries.firstOrNull { it.name == raw }
    }
}

/** What came of a write, in the terms the UI has to report. */
sealed interface WriteResult {
    /** It is in the repository. */
    data object Pushed : WriteResult

    /**
     * It is on the device and in the queue. The next sync sends it, so this is
     * a success with a caveat rather than a failure.
     */
    data class Queued(
        val why: String,
    ) : WriteResult

    /** The file already said this. Nothing was written and nothing is wrong. */
    data object Unchanged : WriteResult

    /** The app will not attempt it, and why. */
    data class Refused(
        val why: String,
    ) : WriteResult
}

/**
 * What came of moving a task, and the move that would take it back.
 *
 * [undo] is there only when taking it back is exact: the dates that moved go
 * back by the same number of days, or the date that was added comes off
 * again. A line whose old value was not a date has no exact way back, so it
 * gets none.
 */
data class Rescheduling(
    val result: WriteResult,
    val undo: Undo? = null,
) {
    /** Move [task] to [date], or take its scheduled date off when null. */
    data class Undo(
        val task: TaskRow,
        val date: String?,
    )
}

/**
 * The one place the app writes to a vault.
 *
 * Three rules hold everything here together:
 *
 * An edit is never a patch. It is stored as an intent -- the line to find, the
 * line to write -- and re-applied to whatever the file says at the moment it
 * reaches the repository, so a change queued on a train lands in the current
 * file rather than reverting a morning of work at the desk.
 *
 * An edit is applied locally first and queued regardless, so writing works with
 * no signal. The queue is flushed at the start of the next sync, before
 * anything is pulled down, because a pull that overwrote a queued edit's file
 * would be indistinguishable from losing it.
 *
 * Nothing is offered unless the host says the credential can push. A read-only
 * deploy key and a `Contents: read-only` token both look exactly like a working
 * credential right up until the push, and finding out then means the person has
 * already typed the thing they wanted to keep.
 */
@Singleton
class VaultWriteRepository
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val vaults: VaultDao,
        private val pending: PendingEditDao,
        private val blobs: BlobDao,
        private val files: VaultFileStore,
        private val indexer: VaultIndexer,
        private val transports: VaultTransports,
        private val log: SyncLog,
        private val gate: VaultGate,
    ) {
        /** How many edits have not reached the repository yet. */
        val queued: Flow<Int> = pending.count()

        /**
         * Whether the app may offer to write at all, per vault.
         *
         * Both halves have to hold: the credential can push, and an author has
         * been named. A commit attributed to nobody in particular is worse than
         * no commit, so the second is not a formality.
         */
        val writable: Flow<Set<Long>> =
            combine(vaults.observe(), settings.settings) { all, current ->
                if (!current.write.hasAuthor) {
                    emptySet()
                } else {
                    all.filter { it.canWrite }.mapTo(mutableSetOf()) { it.id }
                }
            }

        fun canWrite(vaultId: Long): Flow<Boolean> = writable.map { vaultId in it }

        /**
         * Ticks [task] and dates it today.
         *
         * The line is found by the line number the index recorded and then
         * checked against what the index made of it, because a note can grow a
         * paragraph above a task between one sync and the next. When the number
         * no longer points at the right line the whole file is searched for one
         * that reads the same, and only if that fails does the edit refuse.
         */
        suspend fun completeTask(task: TaskRow): WriteResult {
            val vault = writableVault(task.vaultId) ?: return refusal(task.vaultId)
            val text =
                files.readText(vault.id, task.notePath)
                    ?: return WriteResult.Refused("${task.notePath} is not on the device")

            val raw =
                locate(text, task)
                    ?: return WriteResult.Refused(
                        "that task is not in ${task.notePath} any more - refresh and try again",
                    )
            // A repeat becomes two lines: the next occurrence and the one just
            // finished. Where the rule is not one the app will act on it says
            // so rather than dropping the repeat, which would quietly turn a
            // standing job into a one-off.
            if (TaskLine.isRecurring(raw)) {
                val both =
                    TaskLine.completeRecurring(raw, today())
                        ?: return WriteResult.Refused(
                            "this task repeats on a rule the app will not work out for itself - " +
                                "ticking it here could drop the repeat. Obsidian does that part",
                        )
                return apply(
                    vault = vault,
                    path = task.notePath,
                    kind = EditKind.REPLACE_LINE,
                    anchor = raw,
                    replacement = both.joinToString(LINES),
                    summary = "docs(tasks): complete \"${task.text.take(SUBJECT)}\" and schedule the next",
                )
            }
            val completed = TaskLine.complete(raw, today()) ?: return WriteResult.Unchanged

            return apply(
                vault = vault,
                path = task.notePath,
                kind = EditKind.REPLACE_LINE,
                anchor = raw,
                replacement = completed,
                summary = "docs(tasks): complete \"${task.text.take(SUBJECT)}\"",
            )
        }

        /**
         * Moves [task] to [date]: the date it is filed under (due, else
         * scheduled) lands on [date] and its other dates keep their distance
         * from it, or a scheduled date is added when it has neither -- see
         * [TaskLine.reschedule]. [date] null takes off a scheduled date, which
         * is only ever asked for as an undo.
         *
         * Found the way a tick finds it, and queued the same way. What is
         * stored is the task and the date rather than the line to write, so a
         * move queued offline still lands on the task if the line around it
         * changed at the desk in the meantime.
         */
        suspend fun rescheduleTask(
            task: TaskRow,
            date: String?,
        ): Rescheduling {
            val vault = writableVault(task.vaultId) ?: return Rescheduling(refusal(task.vaultId))
            val text =
                files.readText(vault.id, task.notePath)
                    ?: return Rescheduling(WriteResult.Refused("${task.notePath} is not on the device"))
            val lines = text.lines()
            val at =
                TaskLine.locate(lines, task.line, task.text)
                    ?: return Rescheduling(WriteResult.Refused(TaskNotFound(task.text).message.orEmpty()))
            val found = task.copy(line = at)
            val back =
                date?.let { TaskLine.reschedule(lines[at], it) }?.let { moved ->
                    when {
                        moved.previous == null -> Rescheduling.Undo(found, null)
                        isDate(moved.previous) -> Rescheduling.Undo(found, moved.previous)
                        else -> null
                    }
                }
            val result =
                apply(
                    vault = vault,
                    path = task.notePath,
                    kind = EditKind.RESCHEDULE,
                    anchor = task.text,
                    replacement = listOfNotNull("$at", date).joinToString(" "),
                    summary =
                        if (date == null) {
                            "docs(tasks): unschedule \"${task.text.take(SUBJECT)}\""
                        } else {
                            "docs(tasks): move \"${task.text.take(SUBJECT)}\" to $date"
                        },
                )
            val landed = result == WriteResult.Pushed || result is WriteResult.Queued
            return Rescheduling(result, back.takeIf { landed })
        }

        private fun isDate(value: String?): Boolean = value != null && runCatching { LocalDate.parse(value) }.isSuccess

        /**
         * Adds a task under [section] in [path], with this vault's own default
         * dates -- created today, scheduled three days out.
         */
        suspend fun addTask(
            vaultId: Long,
            path: String,
            section: String,
            text: String,
        ): WriteResult {
            val vault = writableVault(vaultId) ?: return refusal(vaultId)
            val body = text.trim()
            if (body.isEmpty()) return WriteResult.Unchanged
            val line = "- [ ] $body ➕ ${today()} ⏳ ${today(SCHEDULE_DAYS)}"

            return apply(
                vault = vault,
                path = path,
                kind = EditKind.ADD_UNDER,
                anchor = section,
                replacement = line,
                summary = "docs(tasks): add \"${body.take(SUBJECT)}\"",
            )
        }

        /**
         * Puts [text] in the scratchpad, under today's date.
         *
         * Dated because a scratchpad that is one unbroken list stops being
         * readable within a week, and timestamped because two captures of the
         * same words on the same day are two captures, not one written twice.
         */
        suspend fun capture(text: String): WriteResult {
            val current = settings.current()
            val vaultId = current.write.scratchpadVaultId.takeIf { it != 0L } ?: activeVaultId(current)
            val vault = writableVault(vaultId) ?: return refusal(vaultId)
            val body = text.trim()
            if (body.isEmpty()) return WriteResult.Unchanged

            val at = LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
            val multiline = body.contains('\n')
            val block = if (multiline) "$at\n\n$body" else "- $at $body"

            return apply(
                vault = vault,
                path = current.write.scratchpadPath,
                kind = if (multiline) EditKind.APPEND else EditKind.ADD_UNDER,
                anchor = if (multiline) "" else today(),
                replacement = block,
                summary = "docs(scratchpad): capture a note",
            )
        }

        /**
         * Sends everything queued, oldest first.
         *
         * Called at the start of a sync rather than at the end: the pull that
         * follows resets an SSH vault's working tree, and an edit still sitting
         * in the queue when that happens loses its local copy. It keeps its
         * place in the queue -- the intent is what is stored -- but the note
         * would visibly revert in front of whoever wrote it.
         *
         * Returns how many were sent.
         */
        suspend fun flush(): Int = gate.withVault { drain(automatic = false) }

        /**
         * The unlocked form, for a caller that already holds the gate.
         *
         * A sync flushes the queue before it pulls, and it does that inside its
         * own lock -- taking the gate again here would deadlock on a mutex that
         * is not reentrant.
         *
         * [automatic] is what tells a scheduled attempt from a person pressing
         * the button: a scheduled one gives up on an edit that has failed
         * [MAX_ATTEMPTS] times, so a change that can never land stops being
         * retried every six hours for ever. A person asking for it always gets
         * one more try.
         */
        internal suspend fun drain(automatic: Boolean = true): Int {
            val queue = pending.all()
            if (queue.isEmpty()) return 0
            log.info("${queue.size} edit(s) waiting to go up")
            var sent = 0
            queue.forEach { edit ->
                val vault = vaults.byId(edit.vaultId)
                if (vault == null) {
                    // Its repository was removed; there is nowhere to send it.
                    pending.delete(edit.id)
                    return@forEach
                }
                if (automatic && edit.attempts >= MAX_ATTEMPTS) {
                    // Said once, not on every refresh: the journal is read to
                    // find out what went wrong, and a line repeated hourly
                    // buries whatever else is in it.
                    return@forEach
                }
                if (send(edit, vault)) sent++
            }
            return sent
        }

        /** Throws the queue away, for when an edit can never land. */
        suspend fun discard(id: Long) = pending.delete(id)

        fun queue(): Flow<List<PendingEditEntity>> = pending.observe()

        /**
         * Writes locally, queues, and tries to send -- in that order.
         *
         * Locally first so the app tells the truth about what it was asked to
         * do even with no signal, and queued before the attempt so a crash
         * between the two leaves an edit to retry rather than one that
         * silently never happened.
         */
        private suspend fun apply(
            vault: VaultEntity,
            path: String,
            kind: EditKind,
            anchor: String,
            replacement: String,
            summary: String,
        ): WriteResult {
            val edit =
                PendingEditEntity(
                    vaultId = vault.id,
                    path = path,
                    kind = kind.name,
                    anchor = anchor,
                    text = replacement,
                    summary = summary,
                    createdAt = System.currentTimeMillis(),
                )
            // Every write funnels through here, so this is the one place that
            // has to wait for a refresh to finish with the files -- except it
            // does not wait. A sync of this vault is minutes; the edit goes in
            // the queue rather than freezing whoever typed it.
            return gate.tryWithVault {
                val transform = transformFor(edit)
                val before = files.readText(vault.id, path)
                val after =
                    try {
                        transform(before)
                    } catch (gone: TaskNotFound) {
                        // Said now, while the person is looking, rather than
                        // queued to fail five times out of sight.
                        return@tryWithVault WriteResult.Refused(gone.message.orEmpty())
                    } ?: return@tryWithVault WriteResult.Unchanged

                val id = pending.insert(edit)
                store(vault.id, path, after)

                val stored = edit.copy(id = id)
                val outcome =
                    if (send(stored, vault)) {
                        WriteResult.Pushed
                    } else {
                        WriteResult.Queued(stored.lastErrorOr("it will go up with the next sync"))
                    }
                // Anything that queued while this held the gate goes now, which
                // is what makes two quick taps behave like two writes rather
                // than one write and one thing waiting for a refresh.
                if (outcome == WriteResult.Pushed) runCatchingUnlessCancelled { drain() }
                outcome
            } ?: queueWhileBusy(edit)
        }

        /**
         * A refresh has the files. The edit is recorded and nothing is written
         * to disk: the sync is checking a tree out underneath, and writing into
         * that is the very race the gate exists to prevent. The next flush --
         * which that same sync runs before it pulls -- applies it.
         */
        private suspend fun queueWhileBusy(edit: PendingEditEntity): WriteResult {
            pending.insert(edit)
            log.info("a refresh has the vault - \"${edit.summary}\" is queued")
            return WriteResult.Queued("a refresh is running; it goes up when that finishes")
        }

        /** True when [edit] reached the repository and left the queue. */
        private suspend fun send(
            edit: PendingEditEntity,
            vault: VaultEntity,
        ): Boolean {
            val current = settings.current()
            if (!vault.canWrite || !current.write.hasAuthor) {
                note(edit, "waiting: ${vault.label} is read-only or no author is set")
                return false
            }
            val transform = transformFor(edit)
            return try {
                val outcome =
                    transports.writer(vault).write(
                        path = edit.path,
                        message = edit.summary,
                        author = current.write.author,
                        edit = TextEdit { transform(it) },
                    )
                when (outcome) {
                    is WriteOutcome.Written -> {
                        store(vault.id, edit.path, outcome.text)
                        log.info("${edit.summary} -> ${outcome.commit.take(SHORT_SHA)}")
                        // What is on the home screen is now a tick behind.
                        afterWrite?.invoke()
                    }

                    WriteOutcome.NotApplicable ->
                        log.info("${edit.path} already said what \"${edit.summary}\" wanted - nothing sent")
                }
                pending.delete(edit.id)
                true
            } catch (cancelled: CancellationException) {
                // Stopped, not failed: no attempt is counted against it, and
                // it is still in the queue for the next sync.
                throw cancelled
            } catch (failure: Exception) {
                note(edit, failure.describeChain())
                log.warn("could not send \"${edit.summary}\": " + failure.describeChain())
                false
            }
        }

        private suspend fun note(
            edit: PendingEditEntity,
            why: String,
        ) = pending.update(edit.copy(attempts = edit.attempts + 1, lastError = why))

        private fun PendingEditEntity.lastErrorOr(fallback: String) = lastError ?: fallback

        /** The pure transform this edit stands for. */
        private fun transformFor(edit: PendingEditEntity): (String?) -> String? =
            when (EditKind.parse(edit.kind)) {
                // Split back out, because a repeat's replacement is two lines
                // and the queue stores an edit as one string.
                EditKind.REPLACE_LINE -> VaultEdits.replaceLineWith(edit.anchor, edit.text.split(LINES))
                EditKind.ADD_UNDER -> VaultEdits.addUnder(edit.anchor, edit.text)
                EditKind.RESCHEDULE -> {
                    val (line, date) = edit.text.split(' ', limit = 2).let { it[0] to it.getOrNull(1) }
                    VaultEdits.reschedule(line.toInt(), edit.anchor, date?.takeIf { it.isNotBlank() })
                }
                EditKind.APPEND, null -> VaultEdits.append(edit.text)
            }

        /**
         * Puts the new text on the device and back through the indexer.
         *
         * The manifest row is written with the sha git would give the content,
         * not a placeholder: the next refresh compares shas, and a wrong one
         * there pulls the file straight back down over the edit.
         */
        private suspend fun store(
            vaultId: Long,
            path: String,
            text: String,
        ) {
            val bytes = text.toByteArray()
            val sha = gitBlobSha(bytes)
            files.write(vaultId, path, bytes)
            blobs.upsert(
                BlobEntity(
                    path = path,
                    vaultId = vaultId,
                    sha = sha,
                    size = bytes.size.toLong(),
                    kind = BlobKind.MARKDOWN,
                    localState = LocalState.DOWNLOADED,
                ),
            )
            indexer.indexChanged(
                vaultId = vaultId,
                changed = listOf(PathAndSha(path, sha)),
                removed = emptyList(),
                // The person wrote this here; it is not news to them.
                ownWrite = true,
            )
        }

        /** Finds the line a stored task came from; see [TaskLine.locate]. */
        private fun locate(
            text: String,
            task: TaskRow,
        ): String? {
            val lines = text.lines()
            return TaskLine.locate(lines, task.line, task.text)?.let { lines[it] }
        }

        private suspend fun writableVault(vaultId: Long): VaultEntity? =
            vaults.byId(vaultId)?.takeIf { it.canWrite && settings.current().write.hasAuthor }

        private suspend fun refusal(vaultId: Long): WriteResult {
            val vault = vaults.byId(vaultId) ?: return WriteResult.Refused("that vault is gone")
            if (!settings.current().write.hasAuthor) {
                return WriteResult.Refused("set a name and email to commit as, in Settings")
            }
            return WriteResult.Refused(
                "${vault.label} is read-only here - its key or token cannot push",
            )
        }

        private suspend fun activeVaultId(current: VaultSettings): Long =
            current.activeVaultId.takeIf { it != 0L }
                ?: vaults.all().firstOrNull { it.enabled }?.id
                ?: 0L

        private fun today(plusDays: Long = 0): String = LocalDate.now().plusDays(plusDays).toString()

        companion object {
            /**
             * Run after an edit reaches the repository, for anything outside
             * the app that shows the vault. Set by the application rather than
             * injected, the same as the sync worker's, because this module
             * deliberately knows nothing about widgets.
             */
            @Volatile
            var afterWrite: (() -> Unit)? = null

            /**
             * How many scheduled attempts an edit gets before it is left alone.
             *
             * An edit that can never land -- its file deleted upstream, the
             * branch protected -- was otherwise retried at the start of every
             * sync for ever, and the only sign of it was a line in Settings.
             */
            const val MAX_ATTEMPTS = 5

            /** How a multi-line replacement is held in one stored field. */
            const val LINES = "\n"
            const val SCHEDULE_DAYS = 3L
            const val SUBJECT = 60
            const val SHORT_SHA = 7
        }
    }
