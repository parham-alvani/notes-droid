package me.parham1995.notes.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import me.parham1995.notes.di.RendererEntryPoint

/**
 * An image's bytes, and whether they are coming.
 *
 * Three states rather than a nullable array, because "not here yet" and "not
 * coming" are different things to draw and a null cannot tell them apart.
 */
@Immutable
class LoadedImage internal constructor(
    val bytes: ByteArray?,
    val failed: Boolean,
) {
    val loading: Boolean get() = bytes == null && !failed
}

/**
 * Reads an image out of the vault, fetching it if this is the first time the
 * note embedding it has been opened.
 *
 * Shared by the inline image and the viewer it opens, so the viewer does not
 * repeat the lookup and cannot drift from it.
 */
@Composable
fun rememberVaultImageBytes(
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
    val state = remember(vaultId, path) { mutableStateOf(LoadedImage(bytes = null, failed = false)) }

    LaunchedEffect(vaultId, path) {
        val loaded = source.bytes(vaultId, path)
        state.value = LoadedImage(bytes = loaded, failed = loaded == null)
    }

    return state
}
