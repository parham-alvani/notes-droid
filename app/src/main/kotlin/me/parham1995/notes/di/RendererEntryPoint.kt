package me.parham1995.notes.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.parham1995.notes.data.VaultFileSource
import me.parham1995.notes.ui.mermaid.MermaidRenderer

/**
 * Composables inside the renderer are reached from deep in a note's block tree,
 * where threading a dependency down from the screen would mean passing it
 * through every block type. An entry point is the narrower option.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RendererEntryPoint {
    fun fileSource(): VaultFileSource

    fun mermaidRenderer(): MermaidRenderer
}
