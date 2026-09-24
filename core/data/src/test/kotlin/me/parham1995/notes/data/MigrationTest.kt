package me.parham1995.notes.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.parham1995.notes.data.database.NotesDatabase
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every version that could be installed, walked to the current schema.
 *
 * A migration is the one thing here that can make an installed copy unusable
 * rather than merely wrong: Room compares the schema it finds against what the
 * entities describe and refuses to open the database at all when they differ.
 * A mistake in one is not a bug someone works around, it is an app that will
 * not launch and cannot be fixed without losing the vault.
 *
 * Room's own `MigrationTestHelper` reads the exported schemas through the asset
 * manager, which AGP does not put on a unit test's asset path. The schemas are
 * plain files, though, so this reads them from disk and does the same job:
 * build the old shape, migrate it, and check what came out matches the shape
 * the entities currently declare.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val schemas = File("schemas/${NotesDatabase::class.java.name}")

    private fun schema(version: Int): JSONObject =
        JSONObject(File(schemas, "$version.json").readText()).getJSONObject("database")

    private val migrations = MIGRATIONS

    @Test
    fun `the schema and the migrations agree on the version`() {
        // Room exports one file per declared version, so the highest on disk
        // is what `@Database` says. A version bumped without a migration to
        // reach it leaves the two apart, and every device already installed
        // has no way forward.
        val exported = schemas.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }
        assertThat(exported.max()).isEqualTo(CURRENT)
    }

    @Test
    fun `the schemas are all checked in`() {
        for (version in 1..CURRENT) {
            assertThat(File(schemas, "$version.json").isFile).isTrue()
        }
    }

    @Test
    fun `every version in the wild migrates to the current schema`() {
        for (from in 1 until CURRENT) {
            val file = File.createTempFile("migration-$from-", ".db").also { it.delete() }
            try {
                BundledSQLiteDriver().open(file.path).use { connection ->
                    build(connection, schema(from))
                    // Not one of Room's tables, so a database built from an
                    // exported schema lacks it -- but every real one has it,
                    // and MIGRATION_3_4 writes to it.
                    NotesDatabase.createSearchIndex(connection)

                    migrations
                        .filter { (range, _) -> range.first >= from }
                        .forEach { (_, migration) -> migration.migrate(connection) }

                    assertMatches(connection, schema(CURRENT), from)
                }
            } finally {
                file.delete()
            }
        }
    }

    /** Recreates the database as it was at some earlier version. */
    private fun build(
        connection: SQLiteConnection,
        schema: JSONObject,
    ) {
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            connection.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (position in 0 until indices.length()) {
                connection.execSQL(
                    indices.getJSONObject(position).getString("createSql").replace(TABLE_NAME, table),
                )
            }
        }
    }

    @Test
    fun `the manifest survives every migration, from every version`() {
        // The shape test above builds an empty database, and an empty table
        // cannot collide, overflow or lose a row -- which is how a migration
        // that could not commit, and left the app unable to start, got
        // through a green suite. This one carries rows the whole way.
        for (from in 1 until CURRENT) {
            val file = File.createTempFile("data-$from-", ".db").also { it.delete() }
            try {
                BundledSQLiteDriver().open(file.path).use { connection ->
                    build(connection, schema(from))
                    NotesDatabase.createSearchIndex(connection)
                    seedBlobs(connection, from)

                    migrations
                        .filter { (range, _) -> range.first >= from }
                        .forEach { (_, migration) -> migration.migrate(connection) }

                    val paths = mutableListOf<String>()
                    connection.prepare("SELECT path FROM blobs ORDER BY path").use {
                        while (it.step()) paths += it.getText(0)
                    }
                    assertWithMessage("the manifest after migrating from %s", from)
                        .that(paths)
                        .containsExactly("README.md", "uploads/logo.png")
                }
            } finally {
                file.delete()
            }
        }
    }

    /** Two manifest rows, written the way the schema at [version] allows. */
    private fun seedBlobs(
        connection: SQLiteConnection,
        version: Int,
    ) {
        // `blobs.vaultId` only exists from 8; before that a blob belonged to
        // the one vault there was.
        val scoped = version >= VAULT_ID_FROM
        if (scoped) {
            // The folder a vault was mounted at became simply its name in 9.
            val named = if (version >= NAMED_FROM) "name" else "mount"
            // And a vault says whether it may be written to from 11, with no
            // default in the table Room creates.
            val writable = if (version >= CAN_WRITE_FROM) ", canWrite" else ""
            val writableValue = if (version >= CAN_WRITE_FROM) ", 0" else ""
            connection.execSQL(
                "INSERT INTO vaults " +
                    "(id, owner, repo, $named, transport, ordinal, enabled, filterVersion, indexVersion$writable) " +
                    "VALUES (1, 'o', 'notes', '', 'REST', 0, 1, 0, 0$writableValue)",
            )
        }
        val columns =
            if (scoped) "(path, vaultId, sha, size, kind, localState)" else "(path, sha, size, kind, localState)"
        val rows =
            if (scoped) {
                "('README.md', 1, 'a', 1, 'MARKDOWN', 'DOWNLOADED'), " +
                    "('uploads/logo.png', 1, 'b', 2, 'IMAGE', 'DOWNLOADED')"
            } else {
                "('README.md', 'a', 1, 'MARKDOWN', 'DOWNLOADED'), " +
                    "('uploads/logo.png', 'b', 2, 'IMAGE', 'DOWNLOADED')"
            }
        connection.execSQL("INSERT INTO blobs $columns VALUES $rows")
    }

    @Test
    fun `two vaults keep a file they both name the same`() {
        // The crash this exists for: one vault mounted at the root and one in
        // a folder, both holding `uploads/logo.png`. Making the paths relative
        // dropped the folder that told them apart, `blobs.path` was the whole
        // key, and the migration could not commit -- so the database never
        // opened again and the app could not start.
        val file = File.createTempFile("two-vaults-", ".db").also { it.delete() }
        try {
            BundledSQLiteDriver().open(file.path).use { connection ->
                build(connection, schema(8))
                NotesDatabase.createSearchIndex(connection)
                connection.execSQL(
                    "INSERT INTO vaults " +
                        "(id, owner, repo, mount, transport, ordinal, enabled, filterVersion, indexVersion) " +
                        "VALUES (1, 'o', 'notes', '', 'REST', 0, 1, 0, 0), " +
                        "(2, 'o', 'documents', 'documents', 'REST', 1, 1, 0, 0)",
                )
                connection.execSQL(
                    "INSERT INTO blobs (path, vaultId, sha, size, kind, localState) VALUES " +
                        "('uploads/logo.png', 1, 'a', 1, 'IMAGE', 'DOWNLOADED'), " +
                        "('documents/uploads/logo.png', 2, 'b', 2, 'IMAGE', 'DOWNLOADED')",
                )

                migrations
                    .filter { (range, _) -> range.first >= 8 }
                    .forEach { (_, migration) -> migration.migrate(connection) }

                val rows = mutableListOf<Pair<Long, String>>()
                connection.prepare("SELECT vaultId, path FROM blobs ORDER BY vaultId").use {
                    while (it.step()) rows += it.getLong(0) to it.getText(1)
                }
                // Both survive, both relative, and the one that was mounted in
                // a folder has lost it.
                assertThat(rows).containsExactly(
                    1L to "uploads/logo.png",
                    2L to "uploads/logo.png",
                )
            }
        } finally {
            file.delete()
        }
    }

    /** Builds [version], seeds it, runs [migration] alone, and checks the result. */
    private fun withData(
        version: Int,
        migration: Migration,
        seed: (SQLiteConnection) -> Unit,
        check: (SQLiteConnection) -> Unit,
    ) {
        val file = File.createTempFile("seeded-$version-", ".db").also { it.delete() }
        try {
            BundledSQLiteDriver().open(file.path).use { connection ->
                build(connection, schema(version))
                NotesDatabase.createSearchIndex(connection)
                seed(connection)
                migration.migrate(connection)
                check(connection)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `notes are retitled after their file names, in the search index too`() =
        withData(
            version = 3,
            migration = NotesDatabase.MIGRATION_3_4,
            seed = { connection ->
                // Titled by their first heading, as the indexer used to. The
                // SQL finds the file name with rtrim rather than a
                // lastIndexOf, which is exactly the kind of thing that is
                // right for `a.md` and wrong two folders down.
                connection.execSQL(
                    "INSERT INTO notes (id, path, parent, name, slug, title, blobSha, size, isFolderNote, " +
                        "isRtl, hasMermaid, hasMath, indexedAt) VALUES " +
                        "(1, 'a.md', '', 'a', 'a', 'A Heading', 's', 1, 0, 0, 0, 0, 0), " +
                        "(2, 'dir/sub/b.md', 'dir/sub', 'b', 'b', 'Another', 's', 1, 0, 0, 0, 0, 0), " +
                        "(3, 'dir/README', 'dir', 'README', 'readme', 'Readme Heading', 's', 1, 0, 0, 0, 0, 0)",
                )
                connection.execSQL(
                    "INSERT INTO note_fts (rowid, title, body) VALUES " +
                        "(1, 'A Heading', 'x'), (2, 'Another', 'y'), (3, 'Readme Heading', 'z')",
                )
            },
            check = { connection ->
                assertThat(texts(connection, "SELECT id || ':' || title FROM notes ORDER BY id"))
                    .containsExactly("1:a", "2:b", "3:README")
                    .inOrder()
                assertThat(texts(connection, "SELECT rowid || ':' || title FROM note_fts ORDER BY rowid"))
                    .containsExactly("1:a", "2:b", "3:README")
                    .inOrder()
            },
        )

    @Test
    fun `existing tasks and vaults start out unable to write`() =
        withData(
            version = 10,
            migration = NotesDatabase.MIGRATION_10_11,
            seed = { connection ->
                connection.execSQL(
                    "INSERT INTO vaults (id, owner, repo, name, transport, ordinal, enabled, filterVersion, " +
                        "indexVersion) VALUES (1, 'o', 'notes', 'notes', 'REST', 0, 1, 0, 0)",
                )
                connection.execSQL(
                    "INSERT INTO tasks (noteId, text, state, section, blockIndex, ordinal, open) VALUES " +
                        "(1, 'a task', 'OPEN', '', 0, 0, 1)",
                )
            },
            check = { connection ->
                // -1, not 0: line 0 is a real line, and a task claiming it
                // would be ticked by editing whatever is actually there.
                assertThat(texts(connection, "SELECT text || ':' || line FROM tasks")).containsExactly("a task:-1")
                // Nothing may write until a sync has asked the host.
                assertThat(texts(connection, "SELECT name || ':' || canWrite FROM vaults")).containsExactly("notes:0")
                assertThat(texts(connection, "SELECT COUNT(*) FROM pending_edits")).containsExactly("0")
            },
        )

    @Test
    fun `what an old reindex left behind is cleared, and nothing else`() {
        // Every full reindex used to leave the vault's previous rows behind:
        // tasks, headings and links of notes that no longer exist, and a
        // second copy of each note in the search index. Seeded here the way
        // an install that has been through a few releases holds them.
        val file = File.createTempFile("orphans-", ".db").also { it.delete() }
        try {
            BundledSQLiteDriver().open(file.path).use { connection ->
                build(connection, schema(11))
                NotesDatabase.createSearchIndex(connection)
                connection.execSQL(
                    "INSERT INTO notes (id, vaultId, path, parent, name, slug, title, blobSha, size, " +
                        "isFolderNote, isRtl, hasMermaid, hasMath, indexedAt, openedAt, scrollIndex) VALUES " +
                        "(10, 1, 'Live.md', '', 'Live', 'live', 'Live', 's', 1, 0, 0, 0, 0, 0, 5, 3), " +
                        "(11, 1, 'Other.md', '', 'Other', 'other', 'Other', 's', 1, 0, 0, 0, 0, 0, NULL, 0)",
                )
                connection.execSQL(
                    "INSERT INTO tasks (noteId, text, state, section, blockIndex, line, ordinal, open) VALUES " +
                        "(10, 'kept', 'OPEN', '', 0, 0, 0, 1), (1, 'orphan', 'OPEN', '', 0, 0, 0, 1), " +
                        "(-1, 'written against -1', 'OPEN', '', 0, 0, 0, 1)",
                )
                connection.execSQL(
                    "INSERT INTO headings (noteId, level, text, slug, ordinal, blockIndex) VALUES " +
                        "(10, 1, 'kept', 'kept', 0, 0), (2, 1, 'orphan', 'orphan', 0, 0)",
                )
                connection.execSQL(
                    "INSERT INTO links (id, srcId, kind, rawTarget, targetId, context, ordinal) VALUES " +
                        "(1, 10, 'WIKILINK', 'Other', 11, '', 0), " +
                        "(2, 10, 'WIKILINK', 'Gone', 3, '', 1), " +
                        "(3, 4, 'WIKILINK', 'Live', 10, '', 0)",
                )
                connection.execSQL(
                    "INSERT INTO note_fts (rowid, title, body) VALUES " +
                        "(10, 'Live', 'body'), (11, 'Other', 'body'), (1, 'Live', 'old copy'), (2, 'Other', 'old copy')",
                )

                NotesDatabase.MIGRATION_11_12.migrate(connection)

                assertThat(texts(connection, "SELECT text FROM tasks")).containsExactly("kept")
                assertThat(texts(connection, "SELECT text FROM headings")).containsExactly("kept")
                assertThat(texts(connection, "SELECT id || ':' || IFNULL(targetId, 'null') FROM links"))
                    .containsExactly("1:11", "2:null")
                assertThat(texts(connection, "SELECT rowid FROM note_fts")).containsExactly("10", "11")
                // The notes themselves, and what the reader did with them, untouched.
                assertThat(texts(connection, "SELECT path || ':' || IFNULL(openedAt, '') FROM notes"))
                    .containsExactly("Live.md:5", "Other.md:")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `tags and aliases arrive empty, and two vaults' notes keep everything they had`() =
        withData(
            version = 12,
            migration = NotesDatabase.MIGRATION_12_13,
            seed = { connection ->
                // Two vaults, each holding a README -- the shape that has
                // broken a migration before -- with the rows derived from them.
                connection.execSQL(
                    "INSERT INTO notes (id, vaultId, path, parent, name, slug, title, blobSha, size, " +
                        "isFolderNote, isRtl, hasMermaid, hasMath, indexedAt, openedAt, scrollIndex) VALUES " +
                        "(1, 1, 'README.md', '', 'README', 'readme', 'README', 'a', 1, 0, 0, 0, 0, 0, 7, 4), " +
                        "(2, 2, 'README.md', '', 'README', 'readme', 'README', 'b', 1, 0, 0, 0, 0, 0, NULL, 0)",
                )
                connection.execSQL(
                    "INSERT INTO tasks (noteId, text, state, section, blockIndex, line, ordinal, open) VALUES " +
                        "(1, 'first', 'OPEN', '', 0, 3, 0, 1), (2, 'second', 'OPEN', '', 0, 5, 0, 1)",
                )
                connection.execSQL(
                    "INSERT INTO links (id, srcId, kind, rawTarget, targetId, context, ordinal) VALUES " +
                        "(1, 1, 'WIKILINK', 'README', 1, '', 0)",
                )
            },
            check = { connection ->
                assertThat(texts(connection, "SELECT COUNT(*) FROM tags")).containsExactly("0")
                assertThat(texts(connection, "SELECT COUNT(*) FROM aliases")).containsExactly("0")
                // Both new tables take a row for either vault's note at once.
                connection.execSQL(
                    "INSERT INTO tags (noteId, name, folded) VALUES (1, 'Idea', 'idea'), (2, 'idea', 'idea')",
                )
                connection.execSQL(
                    "INSERT INTO aliases (noteId, alias, folded) VALUES (1, 'Start', 'start'), (2, 'Start', 'start')",
                )
                assertThat(
                    texts(
                        connection,
                        "SELECT notes.vaultId || ':' || tags.name FROM tags JOIN notes ON notes.id = tags.noteId " +
                            "ORDER BY notes.vaultId",
                    ),
                ).containsExactly("1:Idea", "2:idea").inOrder()
                assertThat(texts(connection, "SELECT vaultId || ':' || path || ':' || scrollIndex FROM notes"))
                    .containsExactly("1:README.md:4", "2:README.md:0")
                assertThat(texts(connection, "SELECT text || ':' || line FROM tasks"))
                    .containsExactly("first:3", "second:5")
                assertThat(texts(connection, "SELECT id || ':' || targetId FROM links")).containsExactly("1:1")
            },
        )

    private fun texts(
        connection: SQLiteConnection,
        sql: String,
    ): List<String> =
        buildList {
            connection.prepare(sql).use { while (it.step()) add(it.getText(0)) }
        }

    /** Every table, column and index the current entities declare must be there. */
    private fun assertMatches(
        connection: SQLiteConnection,
        expected: JSONObject,
        from: Int,
    ) {
        val entities = expected.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")

            val actual = columns(connection, table)
            assertWithMessage("table `%s` is missing after migrating from %s", table, from)
                .that(actual.keys)
                .isNotEmpty()

            val fields = entity.getJSONArray("fields")
            for (position in 0 until fields.length()) {
                val field = fields.getJSONObject(position)
                val column = field.getString("columnName")
                assertWithMessage("`%s`.`%s` after migrating from %s", table, column, from)
                    .that(actual.keys)
                    .contains(column)
                assertWithMessage("affinity of `%s`.`%s` after migrating from %s", table, column, from)
                    .that(actual[column])
                    .isEqualTo(field.getString("affinity"))
            }

            val indices = entity.optJSONArray("indices") ?: continue
            val present = indexNames(connection, table)
            for (position in 0 until indices.length()) {
                val name = indices.getJSONObject(position).getString("name")
                assertWithMessage("index `%s` on `%s` after migrating from %s", name, table, from)
                    .that(present)
                    .contains(name)
            }
        }
    }

    private fun columns(
        connection: SQLiteConnection,
        table: String,
    ): Map<String, String> =
        buildMap {
            connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
                while (statement.step()) {
                    put(statement.getText(NAME_COLUMN), statement.getText(TYPE_COLUMN))
                }
            }
        }

    private fun indexNames(
        connection: SQLiteConnection,
        table: String,
    ): Set<String> =
        buildSet {
            connection.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?").use {
                it.bindText(1, table)
                while (it.step()) add(it.getText(0))
            }
        }

    private companion object {
        /**
         * Derived from the migrations, never written down twice.
         *
         * It used to be a literal kept "alongside" the `@Database` version,
         * which is to say kept until someone forgot -- and a stale one quietly
         * checks every migration against an older schema, so the test passes
         * while the thing it exists to catch walks past it. `the schema and
         * the migrations agree on the version` is what notices a bump that
         * arrived without a migration.
         */
        private val MIGRATIONS: List<Pair<IntRange, Migration>> =
            listOf(
                1..2 to NotesDatabase.MIGRATION_1_2,
                2..3 to NotesDatabase.MIGRATION_2_3,
                3..4 to NotesDatabase.MIGRATION_3_4,
                4..5 to NotesDatabase.MIGRATION_4_5,
                5..6 to NotesDatabase.MIGRATION_5_6,
                6..7 to NotesDatabase.MIGRATION_6_7,
                7..8 to NotesDatabase.MIGRATION_7_8,
                8..9 to NotesDatabase.MIGRATION_8_9,
                9..10 to NotesDatabase.MIGRATION_9_10,
                10..11 to NotesDatabase.MIGRATION_10_11,
                11..12 to NotesDatabase.MIGRATION_11_12,
                12..13 to NotesDatabase.MIGRATION_12_13,
            )

        val CURRENT = MIGRATIONS.maxOf { (range, _) -> range.last }
        const val TABLE_NAME = "\${TABLE_NAME}"

        /** `blobs.vaultId` exists from this version onward. */
        const val VAULT_ID_FROM = 7

        /** `vaults.mount` became `vaults.name` at this version. */
        const val NAMED_FROM = 9

        /** `vaults.canWrite` exists from this version onward. */
        const val CAN_WRITE_FROM = 11

        /** Column positions in `PRAGMA table_info`. */
        const val NAME_COLUMN = 1
        const val TYPE_COLUMN = 2
    }
}
