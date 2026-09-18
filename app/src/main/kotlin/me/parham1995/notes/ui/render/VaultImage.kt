package me.parham1995.notes.ui.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dagger.hilt.android.EntryPointAccessors
import me.parham1995.notes.di.RendererEntryPoint

/**
 * An embedded image.
 *
 * The bytes may not be on the device yet -- images are recorded during sync but
 * fetched when first needed -- so this asks for them and shows a labelled
 * placeholder while they arrive, or if they never do.
 */
@Composable
fun VaultImage(
    path: String,
    alt: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val source =
        remember {
            EntryPointAccessors
                .fromApplication(
                    context.applicationContext,
                    RendererEntryPoint::class.java,
                ).fileSource()
        }
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        val loaded = source.bytes(path)
        if (loaded == null) failed = true else bytes = loaded
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            bytes != null ->
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(bytes)
                            .build(),
                    contentDescription = alt,
                    contentScale = ContentScale.FillWidth,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onClick),
                )

            else ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                ) {
                    Text(
                        text =
                            if (failed) {
                                "Image unavailable - ${path.substringAfterLast(
                                    '/',
                                )}"
                            } else {
                                "Loading image..."
                            },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
    alt?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
