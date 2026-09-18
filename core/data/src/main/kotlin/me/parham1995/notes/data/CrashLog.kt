package me.parham1995.notes.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the last crash where it can be read without a cable.
 *
 * An app cannot prevent every crash, but losing the reason is a choice. This
 * one is sideloaded onto one phone with no store console behind it, so a crash
 * that leaves nothing behind is a crash that will happen again -- the only
 * evidence would be logcat, which is gone by the time anyone thinks to look.
 *
 * One file, overwritten each time. A history of crashes is a feature for a
 * product with a support queue; what is wanted here is the most recent one, in
 * a form that can be shared into a chat.
 */
@Singleton
class CrashLog
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val file: File get() = File(context.filesDir, NAME)

        /**
         * Records [failure] and then lets the process die.
         *
         * Deliberately not a recovery mechanism. An app that catches its own
         * fatal exception and carries on is an app running in a state nobody
         * designed, and the next failure is harder to explain than the first.
         */
        fun install() {
            // Fetched here rather than passed in, so it is visible that the
            // existing handler is kept and delegated to -- the process must
            // still die the way Android expects.
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                runCatching { write(thread, failure) }
                previous?.uncaughtException(thread, failure)
            }
        }

        private fun write(
            thread: Thread,
            failure: Throwable,
        ) {
            val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }
            file.writeText(
                buildString {
                    appendLine("at ${Instant.now()}")
                    appendLine("on thread ${thread.name}")
                    appendLine()
                    append(trace.toString())
                },
            )
        }

        suspend fun read(): String? =
            withContext(Dispatchers.IO) {
                file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
            }

        suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

        private companion object {
            const val NAME = "last-crash.txt"
        }
    }
