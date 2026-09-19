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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.parham1995.notes.R
import me.parham1995.notes.ui.image.rememberVaultImageBytes

/**
 * An embedded image.
 *
 * The bytes may not be on the device yet -- images are recorded during sync but
 * fetched when first needed -- so this asks for them and shows a labelled
 * placeholder while they arrive, or if they never do.
 */
@Composable
fun VaultImage(
    vaultId: Long,
    path: String,
    alt: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val image by rememberVaultImageBytes(vaultId, path)
    val bytes = image.bytes
    val failed = image.failed

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
                                stringResource(R.string.image_unavailable, path.substringAfterLast('/'))
                            } else {
                                stringResource(R.string.image_loading)
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
