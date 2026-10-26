package me.parham1995.notes.feature.capture

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Rich text shared or pasted into a capture arrives as Markdown, and lands where the caret is. */
class CapturePasteTest {
    @Test
    fun `a share with formatting arrives as markdown`() {
        val body = sharedBody("Plan and links", "<p><b>Plan</b> and <a href=\"https://example.com\">links</a></p>")

        assertThat(body).isEqualTo("**Plan** and [links](https://example.com)")
    }

    @Test
    fun `html with nothing worth keeping, or none at all, is the plain text`() {
        assertThat(sharedBody("let a = 1", "<div style=\"color: red\">let a = 1</div>")).isEqualTo("let a = 1")
        assertThat(sharedBody("just text", null)).isEqualTo("just text")
        assertThat(sharedBody(null, null)).isEmpty()
    }

    @Test
    fun `a paste replaces the selection and leaves the caret after it`() {
        val value = TextFieldValue("one TWO three", TextRange(4, 7))

        val pasted = pasteInto(value, "**two**")

        assertThat(pasted.text).isEqualTo("one **two** three")
        assertThat(pasted.selection).isEqualTo(TextRange(11))
    }

    @Test
    fun `a selection made right to left is pasted over all the same`() {
        val pasted = pasteInto(TextFieldValue("abc", TextRange(3, 1)), "X")

        assertThat(pasted.text).isEqualTo("aX")
        assertThat(pasted.selection).isEqualTo(TextRange(2))
    }
}
