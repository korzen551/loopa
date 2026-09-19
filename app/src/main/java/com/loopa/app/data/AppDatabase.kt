package com.loopa.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, Playlist::class, PlaylistItem::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistItemDao(): PlaylistItemDao

    companion object {
        /**
         * Ustawienia przewijania doszly juz po tym, jak w bazie siedzialy playlisty.
         * Dokladamy kolumny zamiast przebudowywac baze, zeby nic nie przepadlo.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN autoAdvance INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN advanceOnFinish INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN useGlobalCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN globalCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE playlists ADD COLUMN usePerItemCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlist_items ADD COLUMN playCount INTEGER NOT NULL DEFAULT 1")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "loopa.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
