package me.parham1995.notes.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance

/**
 * A bottom sheet's window, with system bars that match the sheet.
 *
 * A sheet is a window of its own, and Material picks its bar icons from the
 * sheet's content colour. In naz the sheet's container is `DarkBlack`, which
 * is also `surfaceVariant`, so `contentColorFor` stops there and answers
 * `onSurfaceVariant` -- a grey dark enough to count as dark content. The
 * window then asked for light bars, and the phone drew a pale band with dark
 * buttons under every dark sheet. Decided from the sheet's own colour instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun sheetProperties(): ModalBottomSheetProperties {
    val light = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > HALF
    return ModalBottomSheetProperties(
        isAppearanceLightStatusBars = light,
        isAppearanceLightNavigationBars = light,
    )
}

private const val HALF = 0.5f
