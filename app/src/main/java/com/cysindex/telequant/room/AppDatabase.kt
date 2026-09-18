package com.cysindex.telequant.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Favourite::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favouriteDao(): FavouriteDao

    companion object {

        /**
         * Adds the recorded environment to the places table. Migrated rather
         * than rebuilt: destructive fallback would silently delete every place
         * the user had saved, which is the only data in this app they cannot
         * reproduce.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Favourite ADD COLUMN environment TEXT")
                db.execSQL(
                    "ALTER TABLE Favourite ADD COLUMN capturedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "user_database"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
