package me.parham1995.notes.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        BlobEntity::class,
        SyncStateEntity::class,
        NoteEntity::class,
        LinkEntity::class,
        HeadingEntity::class,
        SyncLogEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun blobDao(): BlobDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun noteDao(): NoteDao

    abstract fun linkDao(): LinkDao

    abstract fun headingDao(): HeadingDao

    abstract fun searchDao(): SearchDao

    abstract fun syncLogDao(): SyncLogDao

    companion object {
        const val NAME = "notes.db"
        const val FTS_TABLE = "note_fts"

        /**
         * The search index, created by hand because Room only annotates FTS3
         * and FTS4 and neither can rank. `sqlite-bundled` ships FTS5 with the
         * app, so this is available regardless of what the device's own SQLite
         * was compiled with.
         *
         * `remove_diacritics 2` is the Unicode-aware setting, which matters for
         * a vault mixing scripts. `columnsize = 0` drops per-row size data the
         * ranking does not need.
         */
        private const val CREATE_FTS =
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS note_fts USING fts5(
                title,
                body,
                tokenize = 'unicode61 remove_diacritics 2',
                prefix = '2 3',
                columnsize = 0
            )
            """

        fun createSearchIndex(connection: SQLiteConnection) {
            connection.execSQL(CREATE_FTS.trimIndent())
        }

        /**
         * Adds the reader's tables to a database that only held the sync
         * manifest. Written by hand so an existing install keeps its synced
         * vault instead of paying for a full re-sync.
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `notes` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`path` TEXT NOT NULL, `parent` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                            "`slug` TEXT NOT NULL, `title` TEXT NOT NULL, `blobSha` TEXT NOT NULL, " +
                            "`size` INTEGER NOT NULL, `isFolderNote` INTEGER NOT NULL, " +
                            "`isRtl` INTEGER NOT NULL, `hasMermaid` INTEGER NOT NULL, " +
                            "`hasMath` INTEGER NOT NULL, `indexedAt` INTEGER NOT NULL, " +
                            "`openedAt` INTEGER)",
                    )
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_path` ON `notes` (`path`)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_parent` ON `notes` (`parent`)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_slug` ON `notes` (`slug`)")

                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `links` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`srcId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `rawTarget` TEXT NOT NULL, " +
                            "`alias` TEXT, `heading` TEXT, `targetId` INTEGER, `context` TEXT NOT NULL, " +
                            "`ordinal` INTEGER NOT NULL)",
                    )
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_links_srcId` ON `links` (`srcId`)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_links_targetId` ON `links` (`targetId`)")

                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `headings` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`noteId` INTEGER NOT NULL, `level` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                            "`slug` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `blockIndex` INTEGER NOT NULL)",
                    )
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_headings_noteId` ON `headings` (`noteId`)")

                    createSearchIndex(connection)
                }
            }

        /** Adds the on-device sync journal. */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_log` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`at` INTEGER NOT NULL, `level` TEXT NOT NULL, `message` TEXT NOT NULL)",
                    )
                }
            }
    }
}
