package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rule the whole of RTL rests on, and it had no tests.
 *
 * Every case here is drawn from what this vault actually contains: Persian
 * prose with English technical terms embedded in it, a line that opens with a
 * hostname and continues in Persian, and headings that mix the two.
 */
class TextDirectionTest {
    @Test
    fun `plain persian reads right to left`() {
        assertThat(TextDirection.of("این یک یادداشت است")).isEqualTo(MdDirection.RTL)
    }

    @Test
    fun `plain english reads left to right`() {
        assertThat(TextDirection.of("this is a note")).isEqualTo(MdDirection.LTR)
    }

    @Test
    fun `an english term inside a persian sentence does not flip it`() {
        // This is the shape most of the vault's Persian is written in, and
        // "contains any RTL character" would get it right while "contains any
        // Latin character" would get it exactly wrong.
        assertThat(TextDirection.of("نیما جان discovery رفت روی production")).isEqualTo(MdDirection.RTL)
    }

    @Test
    fun `a line that opens in english is english, whatever follows`() {
        // First strong character, not majority: a line beginning with a
        // hostname reads left to right even when the sentence is Persian.
        assertThat(TextDirection.of("admin.ntx.ir همین است")).isEqualTo(MdDirection.LTR)
    }

    @Test
    fun `punctuation and digits before the first letter are not strong`() {
        assertThat(TextDirection.of("- [ ] کار")).isEqualTo(MdDirection.RTL)
        assertThat(TextDirection.of("۱۲۳ کار")).isEqualTo(MdDirection.RTL)
        assertThat(TextDirection.of("## عنوان")).isEqualTo(MdDirection.RTL)
    }

    @Test
    fun `text with no letters at all falls back to left to right`() {
        assertThat(TextDirection.of("")).isEqualTo(MdDirection.LTR)
        assertThat(TextDirection.of("123 ... ---")).isEqualTo(MdDirection.LTR)
    }

    @Test
    fun `arabic, hebrew and the presentation forms all count`() {
        assertThat(TextDirection.of("مرحبا")).isEqualTo(MdDirection.RTL)
        assertThat(TextDirection.of("שלום")).isEqualTo(MdDirection.RTL)
        assertThat(TextDirection.of("יִ")).isEqualTo(MdDirection.RTL)
    }

    @Test
    fun `the note-level flag asks a different question from the block rule`() {
        val mixed = "An English note that quotes یک جمله فارسی once."

        // The block reads left to right, but the note contains Persian -- which
        // is what decides whether the browser treats the note as an RTL one.
        assertThat(TextDirection.of(mixed)).isEqualTo(MdDirection.LTR)
        assertThat(TextDirection.containsRtl(mixed)).isTrue()
        assertThat(TextDirection.containsRtl("no persian here")).isFalse()
    }

    @Test
    fun `turkish and accented latin are not mistaken for right to left`() {
        // The vault has `Çöp şiş.md` and `Park Güell.md`; both are Latin.
        assertThat(TextDirection.of("Çöp şiş")).isEqualTo(MdDirection.LTR)
        assertThat(TextDirection.of("Park Güell")).isEqualTo(MdDirection.LTR)
        assertThat(TextDirection.of("İskender kebap")).isEqualTo(MdDirection.LTR)
    }
}
