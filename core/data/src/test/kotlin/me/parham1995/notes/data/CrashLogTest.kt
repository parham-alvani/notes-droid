package me.parham1995.notes.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The browser's crash banner, dismissed, came back on every launch: dismissing
 * it only hid it for the life of the screen, and the crash it was about stayed
 * recorded until it was cleared from Advanced. Found on the device.
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val log = CrashLog(context)
    private val file get() = File(context.filesDir, "last-crash.txt")

    @Before
    fun clean() {
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `a crash once acknowledged is not announced again`() =
        runTest {
            file.writeText("at 2026-09-24T04:02:04Z\non thread main\n\nboom")
            assertThat(log.unseen()).isNotNull()

            log.acknowledge()

            assertThat(log.unseen()).isNull()
            // Still there to be read and shared from Advanced.
            assertThat(log.read()).contains("boom")
        }

    @Test
    fun `a new crash is announced even after an older one was acknowledged`() =
        runTest {
            file.writeText("at 2026-09-24T04:02:04Z\non thread main\n\nboom")
            log.acknowledge()

            file.writeText("at 2026-09-25T09:00:00Z\non thread main\n\nbang")

            assertThat(log.unseen()).contains("bang")
        }
}
