package me.parham1995.notes.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.obsidian.Beautitab
import java.time.LocalDateTime

/** A made-up home page: synthetic notes, quotes and a generated picture. */
internal object HomeFixture {
    val now: LocalDateTime = LocalDateTime.of(2026, 10, 26, 9, 41)

    fun note(
        id: Long,
        path: String,
    ): NoteEntity {
        val name = path.substringAfterLast('/').removeSuffix(".md")
        return NoteEntity(
            id = id,
            vaultId = 1,
            path = path,
            parent = path.substringBeforeLast('/', ""),
            name = name,
            slug = name.lowercase(),
            title = name,
            blobSha = "s",
            size = 1,
            isFolderNote = false,
            isRtl = false,
            hasMermaid = false,
            hasMath = false,
            indexedAt = 0,
        )
    }

    val state =
        HomeUiState(
            settings =
                Beautitab(
                    twentyFourHour = true,
                    greetingText = "Hello, Ada.",
                    showBookmarks = true,
                    showQuote = true,
                ),
            recent =
                listOf(
                    note(1, "Garden/Tomatoes.md"),
                    note(2, "Kitchen/Bread.md"),
                    note(3, "Reading list.md"),
                ),
            bookmarks = listOf(HomeBookmark(4, "Weekly review", "Next week")),
            quote = Beautitab.Quote("Read slowly, and read again.", "Someone"),
        )

    /** A sky over a hill, drawn rather than shipped: the repository is public. */
    fun picture(
        width: Int = 960,
        height: Int = 640,
    ): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sky =
            Paint().apply {
                shader =
                    LinearGradient(
                        0f,
                        0f,
                        0f,
                        height.toFloat(),
                        Color.rgb(255, 170, 90),
                        Color.rgb(60, 90, 170),
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)
        canvas.drawCircle(
            width * 0.7f,
            height * 0.35f,
            height * 0.12f,
            Paint().apply { color = Color.rgb(255, 236, 180) },
        )
        canvas.drawCircle(width * 0.3f, height * 1.25f, height * 0.7f, Paint().apply { color = Color.rgb(40, 110, 70) })
        return bitmap.asImageBitmap()
    }
}
