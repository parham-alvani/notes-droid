package me.parham1995.notes.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Synthetic paths throughout; the repository is public. */
class AttachmentResolverTest {
    private val known =
        setOf(
            "Assets/2024/photo.png",
            "Notes/Trips/map.png",
            "Notes/Trips/Old/map.png",
            "Archive/map.png",
            "Notes/diagram.png",
            "diagram.png",
            "Assets/scan one.png",
            "Assets/a+b.png",
        )

    private fun resolve(
        target: String,
        source: String = "Notes/Trips/Plan.md",
    ) = AttachmentResolver.resolve(target, source, known)

    @Test
    fun `a bare name finds the one file of that name, wherever it is`() {
        // Obsidian's default "shortest path" writes just the file name.
        assertThat(resolve("photo.png")).isEqualTo("Assets/2024/photo.png")
    }

    @Test
    fun `the path from the vault root wins over everything`() {
        assertThat(resolve("diagram.png")).isEqualTo("diagram.png")
        assertThat(resolve("Archive/map.png")).isEqualTo("Archive/map.png")
    }

    @Test
    fun `then the path relative to the note`() {
        assertThat(resolve("Old/map.png")).isEqualTo("Notes/Trips/Old/map.png")
        assertThat(resolve("../diagram.png")).isEqualTo("Notes/diagram.png")
        assertThat(resolve("./map.png")).isEqualTo("Notes/Trips/map.png")
    }

    @Test
    fun `a name several files share goes to the one nearest the note`() {
        assertThat(resolve("map.png")).isEqualTo("Notes/Trips/map.png")
        assertThat(resolve("map.png", source = "Archive/Index.md")).isEqualTo("Archive/map.png")
    }

    @Test
    fun `a partial path matches the end of one`() {
        assertThat(resolve("2024/photo.png", source = "Elsewhere/Note.md")).isEqualTo("Assets/2024/photo.png")
    }

    @Test
    fun `a markdown image's escaped space is a space`() {
        assertThat(resolve("Assets/scan%20one.png")).isEqualTo("Assets/scan one.png")
        assertThat(resolve("scan%20one.png")).isEqualTo("Assets/scan one.png")
        // Not form encoding: a plus is a plus.
        assertThat(resolve("a+b.png")).isEqualTo("Assets/a+b.png")
    }

    @Test
    fun `something that is not in the vault is handed on as written`() {
        assertThat(resolve("https://example.com/a%20b.png")).isEqualTo("https://example.com/a%20b.png")
        assertThat(resolve("missing.png")).isEqualTo("missing.png")
        assertThat(resolve("../../gone.png", source = "A/B/C.md")).isEqualTo("gone.png")
    }

    @Test
    fun `malformed escapes are left alone`() {
        assertThat(AttachmentResolver.percentDecode("100%")).isEqualTo("100%")
        assertThat(AttachmentResolver.percentDecode("a%zzb")).isEqualTo("a%zzb")
        assertThat(AttachmentResolver.percentDecode("%E2%9C%93 done")).isEqualTo("\u2713 done")
    }
}
