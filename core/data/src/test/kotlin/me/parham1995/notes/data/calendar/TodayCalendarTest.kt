package me.parham1995.notes.data.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.CalendarSettings
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultFileStore
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.testDatabase
import me.parham1995.notes.obsidian.CalendarWeeks
import me.parham1995.notes.obsidian.Occurrence
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's meetings, read back from the week notes when the phone's calendars
 * cannot be read -- here, because the test has no calendar permission.
 *
 * Every fixture here is made up. The repository is public; the vault is not.
 */
@RunWith(RobolectricTestRunner::class)
class TodayCalendarTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Europe/Madrid")
    private lateinit var database: NotesDatabase
    private lateinit var files: VaultFileStore
    private lateinit var source: TodayCalendarSource

    @Before
    fun setUp() {
        database = testDatabase()
        files = VaultFileStore(context)
        source = TodayCalendarSource(SettingsStore(context), PhoneCalendars(context), files, database.syncStateDao())
    }

    @After
    fun tearDown() {
        database.close()
        files.root.deleteRecursively()
    }

    private fun at(
        date: LocalDate,
        hour: Int,
    ) = date
        .atTime(hour, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    private suspend fun writeWeek(
        vaultId: Long,
        monday: LocalDate,
        events: List<Occurrence>,
    ) = files.write(
        vaultId,
        CalendarWeeks.pathFor(CalendarWeeks.DEFAULT_FOLDER, monday),
        CalendarWeeks.render(monday, events, zone).toByteArray(),
    )

    @Test
    fun `today is read from the week note, and tomorrow from the next week's`() =
        runTest {
            // A Sunday, so tomorrow is in the next week's note.
            val sunday = LocalDate.of(2026, 10, 25)
            val monday = sunday.plusDays(1)
            writeWeek(
                1L,
                CalendarWeeks.mondayOf(sunday),
                listOf(Occurrence("a", at(sunday, 10), at(sunday, 11), false, "Brunch", null, "Home")),
            )
            writeWeek(
                1L,
                monday,
                listOf(Occurrence("b", at(monday, 9), at(monday, 10), false, "Standup", null, "Work")),
            )

            val day = source.load(CalendarSettings(), activeVault = 1L, today = sunday, zone = zone)

            assertThat(day.live).isFalse()
            assertThat(day.today?.meetings?.map { it.title }).containsExactly("Brunch")
            assertThat(day.today?.free).isEqualTo("09:00–10:00, 11:00–18:00")
            assertThat(day.tomorrowStarts?.title).isEqualTo("Standup")
        }

    @Test
    fun `the notes are read from the vault they are written to`() =
        runTest {
            val today = LocalDate.of(2026, 10, 21)
            writeWeek(2L, CalendarWeeks.mondayOf(today), emptyList())

            assertThat(source.load(CalendarSettings(), activeVault = 1L, today = today, zone = zone).today).isNull()
            val exported =
                source.load(
                    CalendarSettings(exportVaultId = 2L),
                    activeVault = 1L,
                    today = today,
                    zone = zone,
                )
            assertThat(exported.today?.meetings).isEmpty()
        }
}
