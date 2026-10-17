package me.parham1995.notes.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Every accent and markup colour can be read on the background it is drawn
 * on.
 *
 * The light theme shipped painting markdown, date chips and callout titles in
 * naz's colours, which were picked to glow on `#323232`: a done date was
 * spring green on white, a heading pale yellow. Nothing failed, because
 * nothing measured. 3:1 is WCAG's floor for large and bold text, which is
 * what all of these are -- headings, chip labels, callout titles, icons.
 */
class ContrastTest {
    @Test
    fun `the light theme's accents read on paper`() {
        DaylightAccents.named().assertReadableOn(DaylightScheme.background)
    }

    @Test
    fun `the light theme's markup reads on paper`() {
        DaylightMarkup.named().assertReadableOn(DaylightScheme.background)
    }

    @Test
    fun `naz's accents read on naz`() {
        NazAccents.named().assertReadableOn(NazScheme.background)
    }

    @Test
    fun `naz's markup reads on naz`() {
        NazMarkup.named().assertReadableOn(NazScheme.background)
    }

    /**
     * The scheme's text colours are drawn at body size too -- folder names
     * and actionable rows in primary, captions in onSurfaceVariant -- so they
     * are held to WCAG AA for normal text, not the large-text floor above.
     */
    @Test
    fun `each scheme's text colours read as body text`() {
        listOf("light" to DaylightScheme, "dark" to NazScheme).forEach { (name, scheme) ->
            mapOf(
                "primary" to scheme.primary,
                "secondary" to scheme.secondary,
                "error" to scheme.error,
                "onSurface" to scheme.onSurface,
                "onSurfaceVariant" to scheme.onSurfaceVariant,
            ).forEach { (role, colour) ->
                assertWithMessage("$name $role on background")
                    .that(contrast(colour, scheme.background))
                    .isAtLeast(BODY_TEXT)
            }
        }
    }

    @Test
    fun `each scheme is handed its own palette`() {
        assertWithMessage("light accents").that(DaylightScheme.accents).isSameInstanceAs(DaylightAccents)
        assertWithMessage("dark accents").that(NazScheme.accents).isSameInstanceAs(NazAccents)
        assertWithMessage("light markup").that(DaylightScheme.markup).isSameInstanceAs(DaylightMarkup)
        assertWithMessage("dark markup").that(NazScheme.markup).isSameInstanceAs(NazMarkup)
    }

    private fun Map<String, Color>.assertReadableOn(background: Color) =
        forEach { (name, colour) ->
            assertWithMessage("$name against its background").that(contrast(colour, background)).isAtLeast(MINIMUM)
        }

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun AccentColors.named() =
        mapOf(
            "red" to red,
            "orange" to orange,
            "yellow" to yellow,
            "vividYellow" to vividYellow,
            "springGreen" to springGreen,
            "chartreuse" to chartreuse,
            "limeGreen" to limeGreen,
            "blue" to blue,
            "aqua" to aqua,
            "purple" to purple,
            "pink" to pink,
            "grey" to grey,
            "warmGrey" to warmGrey,
        )

    private fun MarkupColors.named() =
        mapOf(
            "Heading1" to Heading1,
            "Heading2" to Heading2,
            "Heading3" to Heading3,
            "Heading4" to Heading4,
            "Heading" to Heading,
            "Strong" to Strong,
            "Italic" to Italic,
            "Strike" to Strike,
            "Raw" to Raw,
            "Link" to Link,
            "Math" to Math,
            "Quote" to Quote,
            "ListMarker" to ListMarker,
        )

    private companion object {
        const val MINIMUM = 3f
        const val BODY_TEXT = 4.5f
    }
}
