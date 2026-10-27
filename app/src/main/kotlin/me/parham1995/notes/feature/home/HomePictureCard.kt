package me.parham1995.notes.feature.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.feature.sync.SectionCard

/**
 * Settings, Reading: the home page's picture, chosen with the system's photo
 * picker -- which needs no storage permission -- or cleared.
 */
@Composable
internal fun HomePictureCard(
    /** Whether a picture of the app's own is set, as opposed to the vault's or none. */
    chosen: Boolean,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val picture by viewModel.picture.collectAsStateWithLifecycle()
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            viewModel.choosePicture(uri) { read ->
                if (!read) {
                    Toast
                        .makeText(
                            context,
                            resources.getString(R.string.home_picture_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    HomePictureSettings(
        chosen = chosen,
        picture = picture,
        onChoose = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onClear = viewModel::clearPicture,
    )
}

@Composable
internal fun HomePictureSettings(
    chosen: Boolean,
    picture: ImageBitmap?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(stringResource(R.string.card_home)) {
        // The picture as the page crops it, at a phone's shape.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(PREVIEW_RATIO)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            picture?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().testTag(PICTURE_TAG),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChoose) { Text(stringResource(R.string.home_picture_choose)) }
            OutlinedButton(onClick = onClear, enabled = chosen) { Text(stringResource(R.string.action_clear)) }
        }
        Text(
            stringResource(R.string.help_home_picture),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PREVIEW_RATIO = 16f / 9f
