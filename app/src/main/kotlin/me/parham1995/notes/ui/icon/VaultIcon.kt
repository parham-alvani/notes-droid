package me.parham1995.notes.ui.icon

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.ui.theme.resolve

/** Lucide's own canvas and stroke, which every icon is drawn against. */
private const val VIEWPORT = 24f
private const val STROKE_WIDTH = 2f

val LocalLucide = staticCompositionLocalOf { LucideSet.EMPTY }

/**
 * Loads the icon set once and hands it to everything below.
 *
 * It is read off the main thread and starts empty, so the first frame draws
 * without icons rather than waiting on a file -- at which point the set arrives
 * and everything under this recomposes.
 */
@Composable
fun ProvideLucide(content: @Composable () -> Unit) {
    val context: Context = LocalContext.current.applicationContext
    val set by produceState(LucideSet.EMPTY, context) { value = LucideSet.load(context) }
    CompositionLocalProvider(LocalLucide provides set, content = content)
}

/**
 * The icon for a note or folder: whatever Obsidian's Iconic plugin assigns it,
 * falling back to [default] when it assigns nothing.
 *
 * The vault has `showAllFileIcons` off, so Obsidian itself shows no icon for an
 * unassigned item. A phone list is not a sidebar, though -- the icon column is
 * also how a row reads as a folder or a note at a glance -- so an unassigned
 * row keeps a muted default glyph instead of a gap.
 */
@Composable
fun VaultIcon(
    spec: IconSpec?,
    default: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    val slot =
        modifier.size(size).semantics {
            contentDescription?.let { this.contentDescription = it }
        }
    when (spec) {
        // Emoji carry their own colour; tinting one would flatten a flag.
        is IconSpec.Emoji ->
            Box(slot, contentAlignment = Alignment.Center) {
                Text(
                    text = spec.text,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    textAlign = TextAlign.Center,
                )
            }

        is IconSpec.Glyph -> LucideIcon(spec.name, spec.color.resolve(tint), slot, default)
        null -> LucideIcon(default, tint, slot, null)
    }
}

/**
 * A Lucide glyph by name, for the places that know which icon they want --
 * callout kinds, task states, the date chips on a task.
 *
 * Emoji would do the same job and this vault's notes are full of them, but at
 * 14sp on a dark background they read as decoration rather than as part of the
 * text. A stroked glyph in the accent colour is the same information without
 * the sticker.
 */
@Composable
fun LucideGlyph(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    val slot =
        modifier.size(size).semantics {
            contentDescription?.let { this.contentDescription = it }
        }
    LucideIcon(name, tint, slot, null)
}

@Composable
private fun LucideIcon(
    name: String,
    tint: Color,
    modifier: Modifier,
    fallback: String?,
) {
    val lucide = LocalLucide.current
    // An icon Lucide has renamed, or one from a set this app does not carry,
    // should still leave the row looking like a row.
    val glyph = lucide.glyph(name) ?: fallback?.let(lucide::glyph)
    if (glyph == null) {
        Box(modifier)
        return
    }
    Canvas(modifier) {
        val factor = size.minDimension / VIEWPORT
        scale(factor, pivot = Offset.Zero) {
            // Scaling the canvas scales the stroke with it, which is what keeps
            // the weight right: Lucide's 2px stroke is 2px *of its own 24px
            // canvas*, not of the size it happens to be drawn at.
            val stroke = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)
            glyph.stroke?.let { drawPath(it, tint, style = stroke) }
            glyph.fill?.let {
                // The source draws these filled *and* stroked -- they are dots
                // given their weight by the inherited stroke, so filling alone
                // renders them a third of the size.
                drawPath(it, tint, style = Fill)
                drawPath(it, tint, style = stroke)
            }
        }
    }
}
