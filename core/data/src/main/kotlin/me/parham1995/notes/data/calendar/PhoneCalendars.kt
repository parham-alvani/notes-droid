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
import android.provider.CalendarContract.ExtendedProperties
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
                        Instances.DESCRIPTION,
                        Instances.ORGANIZER,
                        Instances.AVAILABILITY,
                        Instances.OWNER_ACCOUNT,
                    )
                // IDs are numbers from the settings, never text from outside,
                // so they go into the selection as they are.
                val selection = "${Instances.CALENDAR_ID} IN (${calendars.joinToString()})"
                val owners = mutableMapOf<Long, String>()
                val found =
                    resolver
                        .query(uri, columns, selection, null, "${Instances.BEGIN} ASC")
                        .rows { c ->
                            val cancelled = c.getInt(10) == Events.STATUS_CANCELED
                            val answer = c.getInt(11)
                            if (cancelled || answer == Attendees.ATTENDEE_STATUS_DECLINED) return@rows null
                            val eventId = c.getLong(0)
                            val calendar = c.getLong(1)
                            val owner = c.stringOrNull(15)
                            owner?.let { owners[eventId] = it }
                            val organizer = c.stringOrNull(13)
                            PhoneEvent(
                                eventId = eventId,
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
                                        description = c.stringOrNull(12),
                                        organizer = organizer,
                                        tentative = answer == Attendees.ATTENDEE_STATUS_TENTATIVE,
                                        free = c.getInt(14) == Events.AVAILABILITY_FREE,
                                        mine = owner != null && organizer.equals(owner, ignoreCase = true),
                                    ),
                            )
                        }
                val ids = found.mapTo(mutableSetOf()) { it.eventId }
                val people = attendees(ids, owners)
                val extras = extendedProperties(ids)
                found
                    // Google's working location ("Home", "Office") is an
                    // all-day event on every working day. It says where, not
                    // when, and takes no time. Out of office and focus time
                    // have their own types and stay: they are time.
                    .filter { extras[it.eventId]?.get(EVENT_TYPE) != WORKING_LOCATION }
                    .map {
                        it.copy(
                            occurrence =
                                it.occurrence.copy(
                                    attendees = people[it.eventId].orEmpty(),
                                    conference = extras[it.eventId]?.get(HANGOUT_LINK),
                                ),
                        )
                    }
            }

        /**
         * Who is invited to each of [events], by name where the provider has
         * one and by address otherwise, leaving out the calendar's owner in
         * [owners]: that is the person reading the note. One query for all of
         * them: a week of meetings is a few dozen events, and a query each
         * would be a few dozen round trips to the provider.
         */
        private fun attendees(
            events: Set<Long>,
            owners: Map<Long, String>,
        ): Map<Long, List<String>> {
            if (events.isEmpty()) return emptyMap()
            val columns = arrayOf(Attendees.EVENT_ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL)
            val selection = "${Attendees.EVENT_ID} IN (${events.joinToString()})"
            return resolver
                .query(Attendees.CONTENT_URI, columns, selection, null, null)
                .rows { c ->
                    val event = c.getLong(0)
                    val email = c.stringOrNull(2)
                    if (email != null && email.equals(owners[event], ignoreCase = true)) return@rows null
                    val who = c.stringOrNull(1)?.takeIf { it.isNotBlank() } ?: email ?: return@rows null
                    event to who
                }.groupBy({ it.first }, { it.second })
        }

        /**
         * The extended properties the week notes read, by event: Google's
         * event type and its Meet link. Readable by any app with the calendar
         * permission; only a sync adapter can write them.
         */
        private fun extendedProperties(events: Set<Long>): Map<Long, Map<String, String>> {
            if (events.isEmpty()) return emptyMap()
            val columns = arrayOf(ExtendedProperties.EVENT_ID, ExtendedProperties.NAME, ExtendedProperties.VALUE)
            val selection =
                "${ExtendedProperties.EVENT_ID} IN (${events.joinToString()}) AND " +
                    "${ExtendedProperties.NAME} IN ('$EVENT_TYPE', '$HANGOUT_LINK')"
            return resolver
                .query(ExtendedProperties.CONTENT_URI, columns, selection, null, null)
                .rows { c ->
                    val value = c.stringOrNull(2) ?: return@rows null
                    Triple(c.getLong(0), c.getString(1), value)
                }.groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, pairs) -> pairs.toMap() }
        }

        private companion object {
            /** How Google's sync adapter names an event's type: default, focus time, out of office, working location. */
            const val EVENT_TYPE = "shared:calendarProviderEventType"
            const val WORKING_LOCATION = "WORKING_LOCATION"

            /** The Meet link Google attaches, apart from the description. */
            const val HANGOUT_LINK = "hangoutLink"
        }

        private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

        /** Each row through [row]; a null from it skips the row, and a null cursor is no rows. */
        private fun <T : Any> Cursor?.rows(row: (Cursor) -> T?): List<T> =
            this?.use { c -> buildList { while (c.moveToNext()) row(c)?.let(::add) } } ?: emptyList()
    }
