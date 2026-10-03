package me.parham1995.notes.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When the digest is due, as WorkManager has it.
 *
 * The hour chips were wired to an initial delay under UPDATE, which measures
 * from the original enqueue and is dropped after the first run: choosing a
 * new hour ran the digest at once and left it due a day later, at the minute
 * of the tap. So the test asks WorkManager for the next run time, not the
 * request for its delay.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                // The real worker is Hilt's to build. One that does nothing
                // is enough to have run, which is what the schedule is about.
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = Quiet(appContext, workerParameters)
                    },
                ).build(),
        )
        scheduler = SyncScheduler(context)
    }

    private fun digest() =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(TaskDigestWorker.UNIQUE_WORK)
            .get()
            .single()

    private fun nextRun(): ZonedDateTime =
        Instant.ofEpochMilli(digest().nextScheduleTimeMillis).atZone(ZoneId.systemDefault())

    private fun assertOnTheHour(
        next: ZonedDateTime,
        hour: Int,
    ) {
        assertThat(next.hour).isEqualTo(hour)
        assertThat(next.minute).isEqualTo(0)
        assertThat(next.second).isEqualTo(0)
        assertThat(next.isAfter(ZonedDateTime.now())).isTrue()
        assertThat(next.isBefore(ZonedDateTime.now().plusDays(1))).isTrue()
    }

    @Test
    fun `the digest is due at the chosen hour, within the day`() {
        scheduler.scheduleDigest(22)

        assertOnTheHour(nextRun(), 22)
    }

    @Test
    fun `choosing another hour moves the digest to it`() {
        scheduler.scheduleDigest(7)
        val before = nextRun()

        scheduler.scheduleDigest(18)

        assertThat(before.hour).isEqualTo(7)
        assertOnTheHour(nextRun(), 18)
    }

    /**
     * The case the phone showed: once the work has run, an initial delay is
     * never read again, and the hour chips changed nothing but when the
     * digest ran next -- a day after the tap, to the minute.
     */
    @Test
    fun `after the digest has run, a new hour still moves it`() {
        scheduler.scheduleDigest(7)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setPeriodDelayMet(digest().id)

        scheduler.scheduleDigest(18)

        assertOnTheHour(nextRun(), 18)
    }

    private class Quiet(
        context: Context,
        parameters: WorkerParameters,
    ) : Worker(context, parameters) {
        override fun doWork(): Result = Result.success()
    }
}
