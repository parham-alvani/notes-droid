package me.parham1995.notes.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [BlobEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun blobDao(): BlobDao

    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val NAME = "notes.db"
    }
}
