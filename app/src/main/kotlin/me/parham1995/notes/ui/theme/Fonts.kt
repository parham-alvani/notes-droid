package me.parham1995.notes.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import me.parham1995.notes.R

/**
 * Vazirmatn, for the Persian in this vault.
 *
 * Android already renders Persian: Noto Naskh Arabic ships with the platform
 * and every glyph the vault uses is in it. This is not about coverage, it is
 * about how the script is drawn -- Naskh is a calligraphic face and Vazirmatn
 * is a contemporary one, and Persian prose set in it reads the way the person
 * who wrote it expects to see it.
 *
 * Applied only to text that is actually right-to-left, not as the app's
 * default. It carries Latin glyphs too and could set the whole interface, but
 * then a Persian vault and an English one would look like different apps, and
 * the 2,295 notes here that contain no Persian would be restyled to serve the
 * 112 that do.
 *
 * Two static weights rather than the variable file: 240KB against 236KB, and a
 * static font has no API-level or Compose-version questions attached to it. A
 * request for semi-bold resolves to the nearer of the two.
 *
 * SIL Open Font License 1.1; the text is in `licenses/Vazirmatn-OFL.txt`.
 */
internal val Vazirmatn =
    FontFamily(
        Font(R.font.vazirmatn_regular, FontWeight.Normal),
        Font(R.font.vazirmatn_bold, FontWeight.Bold),
    )
