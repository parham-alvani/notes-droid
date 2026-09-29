package me.parham1995.notes.feature.define

import android.content.Context
import me.parham1995.notes.BuildConfig
import me.parham1995.notes.dictionary.Dictionary
import me.parham1995.notes.dictionary.FileSenseSource
import java.io.File

/**
 * The English dictionary that ships in the APK, made ready to read.
 *
 * The word list is small enough to read straight out of the assets into
 * memory. The senses are not, and a lookup has to jump about in them, which
 * an asset the APK has deflated cannot do -- so they are copied to disk the first time,
 * and again only when an update may have brought a different dictionary.
 * No-backup storage: it is part of the app, not something to restore.
 */
object BundledDictionary {
    private const val ASSETS = "dictionary"

    fun open(context: Context): Dictionary {
        val dir = File(context.noBackupFilesDir, ASSETS)
        val senses = File(dir, "senses-${BuildConfig.VERSION_CODE}")
        if (!senses.exists()) {
            // Whatever an older version left, and a copy cut short by a crash.
            dir.deleteRecursively()
            dir.mkdirs()
            val partial = File(dir, "senses.partial")
            partial.outputStream().use { out -> asset(context, Dictionary.SENSES).use { it.copyTo(out) } }
            check(partial.renameTo(senses)) { "could not put the dictionary in place" }
        }
        return Dictionary(
            words = asset(context, Dictionary.WORDS).use { it.readBytes() },
            senses = FileSenseSource(senses),
            exceptions = asset(context, Dictionary.EXCEPTIONS).use { String(it.readBytes(), Charsets.UTF_8) },
        )
    }

    private fun asset(
        context: Context,
        name: String,
    ) = context.assets.open("$ASSETS/$name")
}
