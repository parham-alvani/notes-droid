package me.parham1995.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * The home page's picture: a copy in the app's own storage, and the one
 * decoded copy of whichever picture the page shows.
 *
 * A copy rather than the address the photo picker hands back. That address
 * is a grant that lasts as long as the activity; a copy needs no permission,
 * survives the photo being deleted from the gallery, and is made small once
 * -- at most [MAX_SIDE] on its long side, as WebP -- rather than decoded at
 * full size every time the page is drawn.
 *
 * The setting ([ReadingSettings.homePicture]) is the copy's file name, which
 * changes with each picture, so anything keyed on it sees a new one.
 */
@Singleton
class HomePictureStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settings: SettingsStore,
    ) {
        private val dir: File get() = File(context.filesDir, DIR)

        /** The chosen picture's file, or null when none is chosen or it is gone. */
        val file: Flow<File?> =
            settings.settings
                .map { it.reading.homePicture }
                .distinctUntilChanged()
                .map { name -> name.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.isFile } }

        /**
         * Makes the picture at [uri] the home page's, as a small copy, and
         * drops the one before it. False, and nothing changed, when [uri] is
         * no picture this phone can read.
         */
        suspend fun choose(uri: Uri): Boolean =
            withContext(Dispatchers.IO) {
                val bitmap =
                    runCatching { decode(ImageDecoder.createSource(context.contentResolver, uri)) }.getOrNull()
                        ?: return@withContext false
                dir.mkdirs()
                val name = "home-${System.currentTimeMillis()}.webp"
                val partial = File(dir, "$name.part")
                val written =
                    runCatching {
                        partial.outputStream().use { bitmap.compress(webp(), QUALITY, it) } &&
                            partial.renameTo(File(dir, name))
                    }.getOrDefault(false)
                bitmap.recycle()
                if (!written) {
                    partial.delete()
                    return@withContext false
                }
                settings.setHomePicture(name)
                sweep(keep = name)
                true
            }

        /** No picture of the app's own: the page falls back to the vault's, or to none. */
        suspend fun clear() =
            withContext(Dispatchers.IO) {
                settings.setHomePicture("")
                sweep(keep = null)
            }

        private val lock = Mutex()
        private var kept: Pair<String, Bitmap>? = null

        /**
         * [file] decoded, no larger than [MAX_SIDE] on its long side and turned
         * as its EXIF says; null when it is no picture.
         *
         * Decoded once and kept: the page asks again every time it is shown, a
         * tablet's pane and a phone's screen ask separately, and a 2560-pixel
         * picture takes long enough to decode to be seen. One is kept, keyed
         * by the file and when it last changed, because one is shown at a time.
         */
        suspend fun bitmap(file: File): Bitmap? =
            lock.withLock {
                val key = "${file.path}@${file.lastModified()}"
                kept?.takeIf { it.first == key }?.let { return@withLock it.second }
                val decoded =
                    withContext(Dispatchers.IO) {
                        runCatching { decode(ImageDecoder.createSource(file)) }.getOrNull()
                    }
                kept = decoded?.let { key to it }
                decoded
            }

        /** Every copy but [keep], so changing the picture never leaves the old one behind. */
        private fun sweep(keep: String?) {
            dir.listFiles()?.filter { it.name != keep }?.forEach { it.delete() }
        }

        private fun decode(source: ImageDecoder.Source): Bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                // In memory the app can read back: a hardware bitmap cannot be
                // compressed, and is drawn no faster for being shown once.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

        @Suppress("DEPRECATION")
        private fun webp(): Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.R
            ) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }

        companion object {
            /** About a phone's long side, or a tablet's: the page is drawn no larger. */
            const val MAX_SIDE = 2560

            private const val DIR = "home"
            private const val QUALITY = 85
        }
    }
