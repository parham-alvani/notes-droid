package me.parham1995.notes.data

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] that lets cancellation through.
 *
 * The standard one catches everything, `CancellationException` included, so a
 * sync that was cancelled -- the worker stopped, the app backgrounded, a newer
 * sync replacing it -- was caught, logged as a failure, and carried on to the
 * next vault or retried. A coroutine that swallows its own cancellation is not
 * cancelled at all.
 */
inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

/**
 * Renders an exception with everything underneath it.
 *
 * JGit wraps transport problems in a `TransportException` whose message is
 * often just "remote hung up unexpectedly" -- a sentence that fits a rejected
 * key, a blocked port, a dropped transfer and a protocol error equally well.
 * Logging only that top-level message cost hours of guessing; the cause chain
 * is where the answer actually lives.
 */
fun Throwable.describeChain(frames: Int = STACK_FRAMES): String =
    buildString {
        var current: Throwable? = this@describeChain
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (depth > 0) append(" <- ")
            append(current::class.qualifiedName?.substringAfterLast('.') ?: "?")
            current.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(MAX_MESSAGE)) }
            current = current.cause
            depth++
        }
        // The deepest frames say which layer gave up, which the messages often
        // do not.
        this@describeChain.deepest().stackTrace.take(frames).forEach { frame ->
            append("\n    at ").append(frame.className.substringAfterLast('.')).append('.').append(frame.methodName)
        }
    }

private fun Throwable.deepest(): Throwable {
    var current: Throwable = this
    var depth = 0
    while (current.cause != null && depth < MAX_DEPTH) {
        current = current.cause!!
        depth++
    }
    return current
}

private const val MAX_DEPTH = 8
private const val MAX_MESSAGE = 220
private const val STACK_FRAMES = 6
