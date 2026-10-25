package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderLogosTest {
    @Test
    fun `a logo is known by its name, whatever its case`() {
        assertThat(FolderLogos.isLogo("Companies/Acme/logo.svg")).isTrue()
        assertThat(FolderLogos.isLogo("Companies/Acme/Logo.PNG")).isTrue()
        assertThat(FolderLogos.isLogo("Companies/Acme/logo-old.svg")).isFalse()
        assertThat(FolderLogos.isLogo("Companies/Acme/catalog.svg")).isFalse()
    }

    @Test
    fun `each folder has its logo, a drawing before a picture`() {
        val logos =
            FolderLogos.byFolder(
                listOf(
                    "Companies/Acme/logo.png",
                    "Companies/Acme/Logo.svg",
                    "Companies/Beta/logo.jpg",
                    "uploads/x.png",
                    "logo.svg",
                ),
            )
        assertThat(logos).containsExactly(
            "Companies/Acme",
            "Companies/Acme/Logo.svg",
            "Companies/Beta",
            "Companies/Beta/logo.jpg",
            "",
            "logo.svg",
        )
    }
}
