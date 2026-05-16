package com.anita.paintgo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Owner::class, Session::class, LocationPoint::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ownerDao(): OwnerDao
    abstract fun sessionDao(): SessionDao
    abstract fun locationPointDao(): LocationPointDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "paintgo.db",
            )
                .addCallback(SeedCallback)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }

        private object SeedCallback : Callback() {
            // Idempotent seed — runs on every open so it covers fresh installs, destructive
            // migrations (where onCreate isn't called), and any future stomped-DB scenarios.
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT INTO Owner (displayName, isSelf)
                    SELECT 'You', 1
                    WHERE NOT EXISTS (SELECT 1 FROM Owner WHERE isSelf = 1)
                    """.trimIndent()
                )
            }
        }
    }
}
