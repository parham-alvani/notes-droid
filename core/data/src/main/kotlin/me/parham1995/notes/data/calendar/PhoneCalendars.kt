package me.parham1995.notes.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import me.parham1995.notes.obsidian.Occurrence
import javax.inject.Inject
import javax.inject.Singleton

/** A calendar on the phone, of any account: Google, DAVx5, a local one. */
data class PhoneCalendar(
    val id: Long,
    val name: String,
    val account: String,
    /** ARGB, as the calendar app draws it. */
    val color: Int,
)

/** One occurrence on the phone, with what the calendar screen needs beyond the week notes. */
data class PhoneEvent(
    val eventId: Long,
    val calendarId: Long,
    val color: Int,
    val occurrence: Occurrence,
)

/**
 * The phone's calendar provider, read-only. Every account the phone syncs is
 * already in it -- each company's Google account, DAVx5 -- so the app reads
 * them all in one place and needs no account of its own.
 *
 * Reads go through `Instances`, which expands a repeat into the times it
 * happens; nothing here parses a repeat rule.
 */
@Singleton
class PhoneCalendars
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val resolver get() = context.contentResolver

        fun permitted(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Emits at once, then whenever any event on the phone changes -- a
         * Google sync, an edit in the calendar app. Nothing is registered
         * without the permission, so this then emits once and stays quiet.
         */
        fun changes(): Flow<Unit> =
            callbackFlow {
                val observer =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }
                if (permitted()) resolver.registerContentObserver(Events.CONTENT_URI, true, observer)
                trySend(Unit)
                awaitClose { resolver.unregisterContentObserver(observer) }
            }.conflate()

        /** Every calendar on the phone, or none without the permission. */
        suspend fun calendars(): List<PhoneCalendar> =
            withContext(Dispatchers.IO) {
                if (!permitted()) return@withContext emptyList()
                val columns =
                    arrayOf(
                        Calendars._ID,
                        Calendars.CALENDAR_DISPLAY_NAME,
                        Calendars.ACCOUNT_NAME,
                        Calendars.CALENDAR_COLOR,
                    )
                resolver.query(Calendars.CONTENT_URI, columns, null, null, null).rows { c ->
                    PhoneCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3))
                }
            }

        /**
         * The occurrences in [calendars] that overlap [span] (epoch millis),
         * without the cancelled ones and the ones declined. [busyOnly] marks
         * the occurrences of those calendars for the week notes.
         */
        suspend fun events(
            calendars: Set<Long>,
            busyOnly: Set<Long>,
            span: LongRange,
        ): List<PhoneEvent> =
            withContext(Dispatchers.IO) {
                if (!permitted() || calendars.isEmpty()) return@withContext emptyList()
                val uri =
                    Instances.CONTENT_URI
                        .buildUpon()
                        .also {
                            ContentUris.appendId(it, span.first)
                            ContentUris.appendId(it, span.last)
                        }.build()
                val columns =
                    arrayOf(
                        Instances.EVENT_ID,
                        Instances.CALENDAR_ID,
                        Instances.BEGIN,
                        Instances.END,
                        Instances.ALL_DAY,
                        Instances.TITLE,
                        Instances.EVENT_LOCATION,
                        Instances.UID_2445,
                        Instances.CALENDAR_DISPLAY_NAME,
                        Instances.DISPLAY_COLOR,
                        Instances.STATUS,
                        Instances.SELF_ATTENDEE_STATUS,
                    )
                // IDs are numbers from the settings, never text from outside,
                // so they go into the selection as they are.
                val selection = "${Instances.CALENDAR_ID} IN (${calendars.joinToString()})"
                resolver
                    .query(uri, columns, selection, null, "${Instances.BEGIN} ASC")
                    .rows { c ->
                        val cancelled = c.getInt(10) == Events.STATUS_CANCELED
                        val declined = c.getInt(11) == Attendees.ATTENDEE_STATUS_DECLINED
                        if (cancelled || declined) return@rows null
                        val calendar = c.getLong(1)
                        PhoneEvent(
                            eventId = c.getLong(0),
                            calendarId = calendar,
                            color = c.getInt(9),
                            occurrence =
                                Occurrence(
                                    uid = c.stringOrNull(7),
                                    begin = c.getLong(2),
                                    end = c.getLong(3),
                                    allDay = c.getInt(4) == 1,
                                    title = c.stringOrNull(5),
                                    location = c.stringOrNull(6),
                                    calendar = c.getString(8).orEmpty(),
                                    busyOnly = calendar in busyOnly,
                                ),
                        )
                    }
            }

        private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

        /** Each row through [row]; a null from it skips the row, and a null cursor is no rows. */
        private fun <T : Any> Cursor?.rows(row: (Cursor) -> T?): List<T> =
            this?.use { c -> buildList { while (c.moveToNext()) row(c)?.let(::add) } } ?: emptyList()
    }
