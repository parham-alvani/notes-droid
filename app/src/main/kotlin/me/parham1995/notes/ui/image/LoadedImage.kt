package me.parham1995.notes.ui.image

import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.di.RendererEntryPoint
import java.io.File

/**
 * An image's file, its shape, and whether it is coming.
 *
 * Three states rather than a nullable file, because "not here yet" and "not
 * coming" are different things to draw and a null cannot tell them apart.
 *
 * A file rather than its bytes. Handing Coil a byte array meant reading the
 * whole image into the heap every time it scrolled back into view, with
 * nothing for Coil to key a cache on; a file it can cache by path and decode at
 * the size it is shown at.
 */
@Immutable
class LoadedImage internal constructor(
    val file: File?,
    val failed: Boolean,
    /**
     * Width over height, read from the file's header, or null when it cannot
     * be -- an SVG, say. Known before the image is decoded, so the space can
     * be reserved and the note does not jump when the picture lands.
     */
    val aspectRatio: Float? = null,
) {
    val loading: Boolean get() = file == null && !failed
}

/**
 * Images already found once, by vault and path.
 *
 * An image scrolled off and back on is recomposed from nothing; starting it
 * from what was found last time lays it out at its real height on the first
 * frame, rather than as a placeholder that then grows under the reader's
 * thumb.
 */
private val found = LruCache<String, LoadedImage>(FOUND_ENTRIES)

/**
 * Finds an image in the vault, fetching it if this is the first time the note
 * embedding it has been opened.
 *
 * Shared by the inline image and the viewer it opens, so the viewer does not
 * repeat the lookup and cannot drift from it.
 */
@Composable
fun rememberVaultImage(
    vaultId: Long,
    path: String,
): State<LoadedImage> {
    val context = LocalContext.current
    val source =
        remember {
            EntryPointAccessors
                .fromApplication(
                    context.applicationContext,
                    RendererEntryPoint::class.java,
                ).fileSource()
        }
    val key = "$vaultId/$path"
    val state = remember(key) { mutableStateOf(found[key] ?: LoadedImage(file = null, failed = false)) }

    LaunchedEffect(key) {
        if (state.value.file != null) return@LaunchedEffect
        val file = source.localFile(vaultId, path)
        val loaded = LoadedImage(file = file, failed = file == null, aspectRatio = file?.let { aspectRatioOf(it) })
        if (file != null) found.put(key, loaded)
        state.value = loaded
    }

    return state
}

/** Width over height from the file's header alone, without decoding a pixel. */
private suspend fun aspectRatioOf(file: File): Float? =
    withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.path, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth.toFloat() / bounds.outHeight
        } else {
            null
        }
    }

private const val FOUND_ENTRIES = 256
