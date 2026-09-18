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

    private val migrations: List<Pair<IntRange, Migration>> =
        listOf(
            1..2 to NotesDatabase.MIGRATION_1_2,
            2..3 to NotesDatabase.MIGRATION_2_3,
            3..4 to NotesDatabase.MIGRATION_3_4,
            4..5 to NotesDatabase.MIGRATION_4_5,
            5..6 to NotesDatabase.MIGRATION_5_6,
            6..7 to NotesDatabase.MIGRATION_6_7,
        )

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
        /** Kept alongside the `@Database(version = …)` it mirrors. */
        const val CURRENT = 7
        const val TABLE_NAME = "\${TABLE_NAME}"

        /** Column positions in `PRAGMA table_info`. */
        const val NAME_COLUMN = 1
        const val TYPE_COLUMN = 2
    }
}
