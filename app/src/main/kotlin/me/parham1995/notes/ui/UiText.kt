package me.parham1995.notes.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Something for the screen to say, decided in a view model.
 *
 * A view model has no composition and so cannot read a resource, which is how
 * its messages came to be English literals whatever the phone was set to. It
 * says which resource instead, and the screen reads it. [Raw] is for words
 * that arrive already written -- an error from the network, a reason from the
 * write path -- and are shown as they are.
 */
sealed interface UiText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
    ) : UiText

    data class Raw(
        val text: String,
    ) : UiText
}

/** The words, in the phone's language where they are ours to translate. */
@Composable
fun UiText.text(): String =
    when (this) {
        is UiText.Resource -> stringResource(id, *args.toTypedArray())
        is UiText.Plural -> pluralStringResource(id, count, count)
        is UiText.Raw -> text
    }
