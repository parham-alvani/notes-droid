package me.parham1995.notes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings are read back through the same DataStore the app uses, because
 * the failure worth catching is a key that is written and never read -- which
 * compiles, runs, and silently reverts to the default on every launch.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SettingsStore(context)

    /**
     * `preferencesDataStore` hands back one instance per file for the life of
     * the JVM, so every test here shares the same store and whatever the last
     * one wrote. Left alone, the defaults test passes or fails depending on
     * which order JUnit happened to pick.
     */
    @Before
    fun clearPreferences() =
        runTest {
            context.settingsDataStore.edit { it.clear() }
        }

    @Test
    fun `background sync is on by default, six-hourly`() =
        runTest {
            val settings = store.current()

            assertThat(settings.backgroundSync).isTrue()
            assertThat(settings.syncIntervalHours).isEqualTo(VaultSettings.DEFAULT_INTERVAL_HOURS)
            // Off by default: a scheduled sync that only ever runs on Wi-Fi is
            // a scheduled sync that mostly does not run.
            assertThat(settings.syncOnWifiOnly).isFalse()
        }

    @Test
    fun `every interval offered can actually be stored and read back`() =
        runTest {
            for (hours in VaultSettings.INTERVAL_CHOICES) {
                store.setSyncIntervalHours(hours)
                assertThat(store.current().syncIntervalHours).isEqualTo(hours)
            }
        }

    @Test
    fun `background sync can be turned off and on again`() =
        runTest {
            store.setBackgroundSync(false)
            assertThat(store.current().backgroundSync).isFalse()

            store.setBackgroundSync(true)
            assertThat(store.current().backgroundSync).isTrue()
        }

    @Test
    fun `an unconfigured vault is not worth scheduling`() =
        runTest {
            // What the application checks before registering periodic work.
            // Scheduling against a blank repository just fails every few hours.
            assertThat(store.current().isConfigured).isFalse()

            store.setRepository("owner", "repo", null)
            assertThat(store.current().isConfigured).isTrue()
        }

    @Test
    fun `wifi-only survives a round trip`() =
        runTest {
            store.setSyncOnWifiOnly(true)
            assertThat(store.current().syncOnWifiOnly).isTrue()
        }
}
