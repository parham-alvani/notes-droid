package me.parham1995.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The home picture is chosen, kept small, read back through the setting, and
 * cleared. The pictures are generated, and WebP like the one the phone has.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePictureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsStore(context)
    private val store = HomePictureStore(context, settings)

    @Before
    fun clearPreferences() =
        runTest {
            context.settingsDataStore.edit { it.clear() }
            File(context.filesDir, "home").deleteRecursively()
        }

    private fun picture(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP_LOSSY,
    ): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint =
            Paint().apply {
                shader =
                    LinearGradient(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        Color.BLUE,
                        Color.YELLOW,
                        Shader.TileMode.CLAMP,
                    )
            }
        Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val file = File.createTempFile("picture", ".img", context.cacheDir)
        file.outputStream().use { bitmap.compress(format, 90, it) }
        return Uri.fromFile(file)
    }

    private fun bounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun `a picture chosen is kept as a copy the setting names`() =
        runTest {
            assertThat(store.choose(picture(1920, 1280))).isTrue()

            val name = settings.current().reading.homePicture
            assertThat(name).endsWith(".webp")
            val file = store.file.first()
            assertThat(file?.name).isEqualTo(name)
            // Already small enough: kept at its own size, not blown up.
            assertThat(bounds(file!!)).isEqualTo(1920 to 1280)
        }

    @Test
    fun `a large picture is made no longer than the limit`() =
        runTest {
            assertThat(store.choose(picture(5120, 2560, Bitmap.CompressFormat.PNG))).isTrue()
            assertThat(bounds(store.file.first()!!)).isEqualTo(
                HomePictureStore.MAX_SIDE to HomePictureStore.MAX_SIDE / 2,
            )
        }

    @Test
    fun `a new picture replaces the old one and clear removes it`() =
        runTest {
            store.choose(picture(800, 600))
            val first = store.file.first()!!
            Thread.sleep(2)
            store.choose(picture(600, 800))
            val second = store.file.first()!!
            assertThat(second).isNotEqualTo(first)
            assertThat(first.exists()).isFalse()

            store.clear()
            assertThat(settings.current().reading.homePicture).isEmpty()
            assertThat(store.file.first()).isNull()
            assertThat(second.exists()).isFalse()
        }

    @Test
    fun `something that is no picture changes nothing`() =
        runTest {
            store.choose(picture(800, 600))
            val before = settings.current().reading.homePicture
            val junk = File.createTempFile("junk", ".webp", context.cacheDir).apply { writeText("not a picture") }

            assertThat(store.choose(Uri.fromFile(junk))).isFalse()
            assertThat(settings.current().reading.homePicture).isEqualTo(before)
        }

    @Test
    fun `a picture is decoded once and kept`() =
        runTest {
            store.choose(picture(1920, 1280))
            val file = store.file.first()!!
            val first = store.bitmap(file)
            assertThat(first).isNotNull()
            assertThat(first!!.width).isEqualTo(1920)
            assertThat(store.bitmap(file)).isSameInstanceAs(first)
        }
}
