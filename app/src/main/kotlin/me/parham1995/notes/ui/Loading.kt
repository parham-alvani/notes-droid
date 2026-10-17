package me.parham1995.notes.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A wait of unknown length: a note opening, a graph laid out, a page of a PDF
 * drawn.
 *
 * Material 3 Expressive's shape-morphing indicator, for waits under a few
 * seconds. A sync, which reports how far it has got, keeps its linear bar.
 * One place for every screen, so they wait the same way.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Loading(modifier: Modifier = Modifier) {
    LoadingIndicator(modifier)
}
