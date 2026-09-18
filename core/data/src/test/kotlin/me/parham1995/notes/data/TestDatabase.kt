package me.parham1995.notes.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import me.parham1995.notes.data.database.NotesDatabase

/**
 * An in-memory database wired exactly as the app wires the real one.
 *
 * The driver and the hand-made FTS5 table are the parts that matter: Room's
 * `withTransaction` throws under the driver API, and the search table is not
 * one of Room's, so a test built any other way would miss both -- which is
 * precisely how they reached a device.
 */
fun testDatabase(): NotesDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NotesDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onCreate(connection: SQLiteConnection) = NotesDatabase.createSearchIndex(connection)

                override fun onOpen(connection: SQLiteConnection) = NotesDatabase.createSearchIndex(connection)
            },
        ).build()
