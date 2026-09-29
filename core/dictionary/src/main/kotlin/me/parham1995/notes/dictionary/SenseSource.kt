package me.parham1995.notes.dictionary

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** The line of the senses file that starts at a byte offset, without its newline. */
fun interface SenseSource {
    fun lineAt(offset: Long): String
}

/**
 * The senses read from disk as they are asked for. The file is ten megabytes
 * and a lookup wants a handful of lines from it, so it is never held in memory.
 */
class FileSenseSource(
    file: File,
) : SenseSource,
    Closeable {
    private val file = RandomAccessFile(file, "r")

    @Synchronized
    override fun lineAt(offset: Long): String {
        file.seek(offset)
        val line = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        while (true) {
            val read = file.read(buffer)
            if (read <= 0) break
            val end = (0 until read).firstOrNull { buffer[it] == NEWLINE }
            line.write(buffer, 0, end ?: read)
            if (end != null) break
        }
        return String(line.toByteArray(), Charsets.UTF_8)
    }

    override fun close() = file.close()

    private companion object {
        const val CHUNK = 512
        const val NEWLINE = '\n'.code.toByte()
    }
}
