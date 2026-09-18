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
    version = 5,
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

    abstract fun indexDao(): IndexDao

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

        /**
         * Retitles every note after its file name.
         *
         * In Obsidian the title *is* the file name; this was storing the first
         * H1 instead, so any note whose heading differed showed the wrong thing
         * in the browser, in search results and in its own top bar. Fixing the
         * indexer only helps notes that get reindexed, and nothing upstream
         * changes just because the app was updated -- so existing rows are
         * corrected in place rather than waiting for a re-sync.
         *
         * `rtrim(path, <every non-slash character in it>)` leaves the path up
         * to and including its last slash, which is how the file name is found
         * without a lastIndexOf.
         */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "UPDATE notes SET title = CASE WHEN path LIKE '%.md' THEN " +
                            "substr(substr(path, length(rtrim(path, replace(path, '/', ''))) + 1), 1, " +
                            "length(substr(path, length(rtrim(path, replace(path, '/', ''))) + 1)) - 3) " +
                            "ELSE substr(path, length(rtrim(path, replace(path, '/', ''))) + 1) END",
                    )
                    // The search index keeps its own copy of the title, and it
                    // is weighted ten times the body, so a stale one there is
                    // worse than a stale one on screen.
                    connection.execSQL(
                        "UPDATE note_fts SET title = " +
                            "(SELECT title FROM notes WHERE notes.id = note_fts.rowid)",
                    )
                }
            }

        /**
         * Records which filter version built the manifest.
         *
         * Existing rows get 0, which is deliberately not the current version:
         * every device that synced before the Iconic config was vault content
         * has a manifest missing it, and zero is how the next sync knows to go
         * and look.
         */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `sync_state` ADD COLUMN `filterVersion` INTEGER NOT NULL DEFAULT 0",
                    )
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
