package me.parham1995.notes.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The scopes a shared element needs, handed down rather than threaded through
 * every screen's parameters.
 *
 * Both are null outside a transition host -- in a test, in a widget's
 * preview, in a screen composed on its own -- and the modifiers below are
 * then no-ops, so nothing has to know whether it is being animated.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The navigation destination's own visibility, for elements shared across a navigation. */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** The key a note's title is shared under, from the row that opened it to the bar above it. */
fun noteTitleKey(noteId: Long): String = "note-title-$noteId"

/** The key a picture is shared under, from its place in the note to the viewer. */
fun imageKey(
    vaultId: Long,
    path: String,
): String = "image-$vaultId-$path"

/**
 * Shares this element's bounds under [key] with whatever else claims it in
 * the other state, so a title grows from the row it was tapped in into the
 * bar it ends up in. Text keeps its own measured size through the move, so
 * it is not squeezed to fit the bounds halfway.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBoundsIn(
    key: Any,
    visibility: AnimatedVisibilityScope? = LocalNavAnimatedVisibilityScope.current,
): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val scope = visibility ?: return this
    return with(shared) {
        this@sharedBoundsIn
            .sharedBounds(rememberSharedContentState(key), scope)
            .skipToLookaheadSize()
    }
}

/**
 * Shares this element itself under [key]: the same picture drawn in two
 * places is moved between them rather than cross-faded.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedElementIn(
    key: Any,
    visibility: AnimatedVisibilityScope? = LocalNavAnimatedVisibilityScope.current,
): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val scope = visibility ?: return this
    return with(shared) { this@sharedElementIn.sharedElement(rememberSharedContentState(key), scope) }
}
