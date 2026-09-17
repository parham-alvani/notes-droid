package me.parham1995.notes.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.HeadingDao
import me.parham1995.notes.data.database.LinkDao
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.SearchDao
import me.parham1995.notes.data.database.SyncLogDao
import me.parham1995.notes.data.database.SyncStateDao
import okhttp3.OkHttpClient
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): NotesDatabase =
        Room
            .databaseBuilder(context, NotesDatabase::class.java, NotesDatabase.NAME)
            // Ships SQLite with the app rather than using the platform's. The
            // version varies by ROM, and FTS5 -- which search needs -- is not
            // guaranteed to be compiled in. Bundling makes it identical
            // everywhere and lets the queries be tested on the JVM.
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(connection: SQLiteConnection) = NotesDatabase.createSearchIndex(connection)

                    // Re-asserted on open: the FTS table is not part of Room's
                    // schema, so nothing else would recreate it if it were lost.
                    override fun onOpen(connection: SQLiteConnection) = NotesDatabase.createSearchIndex(connection)
                },
            ).build()

    @Provides
    fun blobDao(database: NotesDatabase): BlobDao = database.blobDao()

    @Provides
    fun syncStateDao(database: NotesDatabase): SyncStateDao = database.syncStateDao()

    @Provides
    fun noteDao(database: NotesDatabase): NoteDao = database.noteDao()

    @Provides
    fun linkDao(database: NotesDatabase): LinkDao = database.linkDao()

    @Provides
    fun headingDao(database: NotesDatabase): HeadingDao = database.headingDao()

    @Provides
    fun searchDao(database: NotesDatabase): SearchDao = database.searchDao()

    @Provides
    fun syncLogDao(database: NotesDatabase): SyncLogDao = database.syncLogDao()

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
            .build()

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
}
