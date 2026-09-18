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
        TaskEntity::class,
        VaultEntity::class,
    ],
    version = 10,
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

    abstract fun taskDao(): TaskDao

    abstract fun vaultDao(): VaultDao

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

        /**
         * Adds the tasks table, and a version for the indexer that fills it.
         *
         * The table starts empty and cannot be filled here -- the rows come
         * from parsing markdown, which a migration cannot do. `indexVersion`
         * is how the app finds out: a sync only reparses files that changed,
         * so without it the table would stay empty on an existing install
         * until some unrelated note happened to be edited.
         */
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `tasks` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`noteId` INTEGER NOT NULL, `text` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                            "`section` TEXT NOT NULL, `blockIndex` INTEGER NOT NULL, " +
                            "`ordinal` INTEGER NOT NULL, `open` INTEGER NOT NULL, " +
                            "`actionableOn` TEXT, `scheduled` TEXT, `due` TEXT, `done` TEXT, " +
                            "`recurring` TEXT)",
                    )
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_noteId` ON `tasks` (`noteId`)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_open` ON `tasks` (`open`)")
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_tasks_actionableOn` ON `tasks` (`actionableOn`)",
                    )
                    connection.execSQL(
                        "ALTER TABLE `sync_state` ADD COLUMN `indexVersion` INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        /**
         * Adds the table of repositories, and scopes the manifest to one.
         *
         * Existing rows get `vaultId = 0`, and the vault seeded from the old
         * single-repository settings is given that id, so an install that has
         * only ever read one repository carries on with the manifest it
         * already had. Nothing moves on disk: that vault mounts at the root,
         * so every path it has ever recorded is still correct.
         */
        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `vaults` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`owner` TEXT NOT NULL, `repo` TEXT NOT NULL, `branch` TEXT, " +
                            "`mount` TEXT NOT NULL, `transport` TEXT NOT NULL, " +
                            "`ordinal` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, " +
                            "`headCommit` TEXT, `etagRef` TEXT, `lastSyncAt` INTEGER, " +
                            "`lastError` TEXT, `filterVersion` INTEGER NOT NULL, " +
                            "`indexVersion` INTEGER NOT NULL)",
                    )
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vaults_mount` ON `vaults` (`mount`)")
                    connection.execSQL("ALTER TABLE `blobs` ADD COLUMN `vaultId` INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_blobs_vaultId` ON `blobs` (`vaultId`)")
                }
            }

        /** Remembers where each note was left. */
        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE `notes` ADD COLUMN `scrollIndex` INTEGER NOT NULL DEFAULT 0")
                }
            }

        /**
         * Separates the vaults.
         *
         * Repositories used to be mounted as folders inside one tree, which
         * made every table work untouched and made the app wrong: a document
         * archive appeared inside the notes, links resolved across repositories
         * that have nothing to do with each other, and search mixed them. They
         * are now separate -- their own files, index, search and tasks -- and a
         * note's path is relative to the vault holding it.
         *
         * The tables derived from the notes are dropped and recreated rather
         * than altered. Every one of them is rebuilt by parsing what is on
         * disk, which takes seconds, and recreating them is the only way to be
         * certain the schema matches what the entities now declare. The
         * manifest is kept, because re-fetching 222MB to change a column would
         * not be.
         */
        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(connection: SQLiteConnection) {
                    // A mount was a folder name; it is now just a name. Renamed
                    // before the blanks are filled, because which vaults had an
                    // empty mount is what says whose paths need shortening.
                    connection.execSQL("ALTER TABLE `vaults` RENAME COLUMN `mount` TO `name`")
                    connection.execSQL("DROP INDEX IF EXISTS `index_vaults_mount`")
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vaults_name` ON `vaults` (`name`)")

                    // Paths are made relative to their vault in 9 -> 10, not
                    // here. `blobs.path` is still the whole primary key at
                    // this version, and stripping the prefixes lands two
                    // vaults' files on one key -- so this step could not
                    // commit, the database stayed unopenable, and the app
                    // could not start. The rewrite belongs with the key that
                    // makes it legal.
                    connection.execSQL("UPDATE `vaults` SET `name` = `repo` WHERE `name` = ''")

                    connection.execSQL("DROP TABLE IF EXISTS `tasks`")
                    connection.execSQL("DROP TABLE IF EXISTS `links`")
                    connection.execSQL("DROP TABLE IF EXISTS `headings`")
                    connection.execSQL("DROP TABLE IF EXISTS `notes`")
                    createNotes(connection)
                    connection.execSQL("DELETE FROM note_fts")
                }
            }

        /**
         * Keys the manifest by its vault as well as its path, and makes the
         * paths relative at the same time.
         *
         * These are one change, not two. A path only stops being unique once
         * it loses the folder that named its repository, and it can only lose
         * that folder once the vault is part of the key -- which is why
         * `blobs` is rebuilt rather than updated in place. SQLite cannot add a
         * column to a primary key.
         *
         * `INSERT OR REPLACE` rather than a plain insert: within one vault two
         * different paths cannot strip to the same one, so there is nothing to
         * lose, and a manifest row is rebuilt by the next sync in any case. It
         * is not worth another version that cannot open.
         */
        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `blobs_new` (" +
                            "`path` TEXT NOT NULL, `vaultId` INTEGER NOT NULL, `sha` TEXT NOT NULL, " +
                            "`size` INTEGER NOT NULL, `kind` TEXT NOT NULL, `localState` TEXT NOT NULL, " +
                            "PRIMARY KEY(`vaultId`, `path`))",
                    )
                    // Only a path that still carries its vault's folder is
                    // shortened. An install that reached 9 with one vault had
                    // nothing to strip, and running it twice would eat a real
                    // directory that happens to share the vault's name.
                    connection.execSQL(
                        "INSERT OR REPLACE INTO `blobs_new` (`path`, `vaultId`, `sha`, `size`, `kind`, `localState`) " +
                            "SELECT CASE WHEN v.name != '' AND b.path LIKE v.name || '/%' " +
                            "THEN substr(b.path, length(v.name) + 2) ELSE b.path END, " +
                            "b.vaultId, b.sha, b.size, b.kind, b.localState " +
                            "FROM blobs b LEFT JOIN vaults v ON v.id = b.vaultId",
                    )
                    connection.execSQL("DROP TABLE `blobs`")
                    connection.execSQL("ALTER TABLE `blobs_new` RENAME TO `blobs`")
                    connection.execSQL("DROP INDEX IF EXISTS `index_blobs_vaultId`")
                }
            }

        /** The tables derived from a note, exactly as the entities declare them. */
        private fun createNotes(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `notes` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`vaultId` INTEGER NOT NULL, `path` TEXT NOT NULL, `parent` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `slug` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`blobSha` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                    "`isFolderNote` INTEGER NOT NULL, `isRtl` INTEGER NOT NULL, " +
                    "`hasMermaid` INTEGER NOT NULL, `hasMath` INTEGER NOT NULL, " +
                    "`indexedAt` INTEGER NOT NULL, `openedAt` INTEGER, " +
                    "`scrollIndex` INTEGER NOT NULL DEFAULT 0)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_vaultId_path` ON `notes` (`vaultId`, `path`)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_vaultId` ON `notes` (`vaultId`)")
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

            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `tasks` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`noteId` INTEGER NOT NULL, `text` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                    "`section` TEXT NOT NULL, `blockIndex` INTEGER NOT NULL, " +
                    "`ordinal` INTEGER NOT NULL, `open` INTEGER NOT NULL, " +
                    "`actionableOn` TEXT, `scheduled` TEXT, `due` TEXT, `done` TEXT, " +
                    "`recurring` TEXT)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_noteId` ON `tasks` (`noteId`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_open` ON `tasks` (`open`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_actionableOn` ON `tasks` (`actionableOn`)")
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
