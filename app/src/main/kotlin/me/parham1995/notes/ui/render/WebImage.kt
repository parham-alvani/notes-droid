package me.parham1995.notes.ui.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.parham1995.notes.R
import me.parham1995.notes.markdown.RemoteImages
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript

/**
 * A picture that is not a file of the vault: one from the web,
 * `![chart](https://…)`, or a `data:` one written into the note.
 *
 * Showing one from the web asks its site for it, and the site learns the
 * note was opened. With Pictures from the web off ([LocalReading]'s
 * `remoteImages`), it is a placeholder naming the site and the picture's
 * words until it is tapped, and then that one picture loads, for as long as
 * this note is on screen -- `remote-images.md` in notes-linux's
 * `docs/specs/`. A `data:` picture asks no one, and is always drawn.
 */
@Composable
fun WebImage(
    src: String,
    alt: String?,
    modifier: Modifier = Modifier,
    width: Int? = null,
    height: Int? = null,
) {
    val remote = RemoteImages.isRemote(src)
    var asked by rememberSaveable(src) { mutableStateOf(false) }
    val shown = !remote || asked || LocalReading.current.remoteImages
    val sized =
        (if (width != null) Modifier.widthIn(max = width.dp).fillMaxWidth() else Modifier.fillMaxWidth())
            .let { base ->
                if (width != null &&
                    height != null &&
                    height > 0
                ) {
                    base.aspectRatio(width.toFloat() / height)
                } else {
                    base
                }
            }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (shown) {
            AsyncImage(
                model = src,
                contentDescription = alt,
                contentScale = if (height != null) ContentScale.Fit else ContentScale.FillWidth,
                modifier = sized.clip(RoundedCornerShape(12.dp)),
            )
        } else {
            RemoteImagePlaceholder(src, alt, onLoad = { asked = true })
        }
    }
    if (shown) {
        alt?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/**
 * What stands in for a picture from the web that has not been asked for: a
 * picture's icon, the site it would come from, and its words. A tap loads it.
 */
@Composable
fun RemoteImagePlaceholder(
    src: String,
    alt: String?,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = RemoteImages.host(src)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    onClickLabel = stringResource(R.string.remote_image_load, host),
                    role = Role.Button,
                    onClick = onLoad,
                ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideGlyph("image", size = 20.dp)
            Column {
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelMedium.inScript(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                alt?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall.inScript(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
