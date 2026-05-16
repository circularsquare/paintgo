package com.anita.paintgo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Owner::class, Session::class, LocationPoint::class],
    version = 1,
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
                .build()
                .also { instance = it }
        }

        private object SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO Owner (displayName, isSelf) VALUES ('You', 1)")
            }
        }
    }
}
