package me.parham1995.notes.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockTest {
    private var clock = 1_000L
    private val lock = AppLock { clock }

    @Test
    fun `a fresh start is locked`() {
        assertThat(lock.unlocked.value).isFalse()
        assertThat(lock.isOpen()).isFalse()
    }

    @Test
    fun `a short trip away stays unlocked`() {
        lock.unlock()
        lock.hidden()
        clock += AppLock.GRACE_MS
        lock.shown()

        assertThat(lock.unlocked.value).isTrue()
    }

    @Test
    fun `a long one locks`() {
        lock.unlock()
        lock.hidden()
        clock += AppLock.GRACE_MS + 1
        lock.shown()

        assertThat(lock.unlocked.value).isFalse()
    }

    @Test
    fun `other apps keep seeing the vaults for a while after the app is left`() {
        lock.unlock()
        lock.hidden()
        // Past the app's own minute, as attaching a file from a message takes.
        clock += AppLock.GRACE_MS * 3
        assertThat(lock.isOpen()).isTrue()

        clock += AppLock.PICKER_GRACE_MS
        assertThat(lock.isOpen()).isFalse()
    }

    @Test
    fun `coming back without having left does nothing`() {
        lock.unlock()
        clock += AppLock.GRACE_MS * 10
        lock.shown()

        assertThat(lock.unlocked.value).isTrue()
    }
}
