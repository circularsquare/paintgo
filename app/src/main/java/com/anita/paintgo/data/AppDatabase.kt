package com.anita.paintgo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Owner::class,
        Session::class,
        LocationPoint::class,
        ChunkCoverage::class,
        ChunkRegionCoverage::class,
        ChunkFog::class,
        SessionStat::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ownerDao(): OwnerDao
    abstract fun sessionDao(): SessionDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun chunkCoverageDao(): ChunkCoverageDao
    abstract fun chunkRegionCoverageDao(): ChunkRegionCoverageDao
    abstract fun chunkFogDao(): ChunkFogDao
    abstract fun sessionStatDao(): SessionStatDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        const val DB_NAME = "paintgo.db"

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .addCallback(SeedCallback)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }

        // Close the open database and drop the cached instance so the next get() reopens
        // it from disk. Used by backup restore, which swaps the underlying .db file out
        // from under Room: any open handle must be released first, and the next get() must
        // build a fresh instance bound to the restored file.
        fun closeAndReset() = synchronized(this) {
            instance?.close()
            instance = null
        }

        // v2 → v3: introduces banded grid (lat-band-aware cellY). LocationPoint gets a
        // `band` column and the dedup unique index changes from (sessionId, cellX, cellY)
        // to (sessionId, band, cellX, cellY). cellX/cellY get recomputed from lat/lng
        // for every existing row using the new banded formula. INSERT OR IGNORE so any
        // rows that now collapse to the same new cell silently dedup.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE LocationPoint_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        accuracy REAL NOT NULL,
                        band INTEGER NOT NULL,
                        cellX INTEGER NOT NULL,
                        cellY INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES Session(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                // Unique index up front so INSERT OR IGNORE dedupes during the copy.
                db.execSQL(
                    "CREATE UNIQUE INDEX index_LocationPoint_new_sessionId_band_cellX_cellY " +
                        "ON LocationPoint_new(sessionId, band, cellX, cellY)"
                )
                db.execSQL(
                    "CREATE INDEX index_LocationPoint_new_sessionId ON LocationPoint_new(sessionId)"
                )

                val insert = db.compileStatement(
                    "INSERT OR IGNORE INTO LocationPoint_new(" +
                        "id, sessionId, lat, lng, timestamp, accuracy, band, cellX, cellY) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)"
                )
                db.query("SELECT id, sessionId, lat, lng, timestamp, accuracy FROM LocationPoint")
                    .use { c ->
                        while (c.moveToNext()) {
                            val lat = c.getDouble(2)
                            val lng = c.getDouble(3)
                            val band = bandOf(lat)
                            val cx = cellXOf(lat)
                            val cy = cellYOf(lng, band)
                            insert.clearBindings()
                            insert.bindLong(1, c.getLong(0))
                            insert.bindLong(2, c.getLong(1))
                            insert.bindDouble(3, lat)
                            insert.bindDouble(4, lng)
                            insert.bindLong(5, c.getLong(4))
                            insert.bindDouble(6, c.getDouble(5))
                            insert.bindLong(7, band.toLong())
                            insert.bindLong(8, cx.toLong())
                            insert.bindLong(9, cy.toLong())
                            insert.executeInsert()
                        }
                    }

                db.execSQL("DROP TABLE LocationPoint")
                db.execSQL("ALTER TABLE LocationPoint_new RENAME TO LocationPoint")
                // SQLite carries indexes across the rename, but their names still say
                // "_new_". Drop + recreate so Room finds the names it expects.
                db.execSQL("DROP INDEX IF EXISTS index_LocationPoint_new_sessionId_band_cellX_cellY")
                db.execSQL("DROP INDEX IF EXISTS index_LocationPoint_new_sessionId")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_LocationPoint_sessionId_band_cellX_cellY " +
                        "ON LocationPoint(sessionId, band, cellX, cellY)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_LocationPoint_sessionId ON LocationPoint(sessionId)"
                )
            }
        }

        // v3 → v4: shrinks the latitude band size (10° → 5°), which halves the within-
        // band aspect-ratio skew of dedup cells. Schema is identical; only band/cellX/
        // cellY values change. Recompute in place: drop the unique index so duplicates
        // don't block UPDATE, rewrite cells per row, dedup-and-delete keeping min(id)
        // per (sessionId, band, cellX, cellY), then re-create the unique index.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_LocationPoint_sessionId_band_cellX_cellY")

                val update = db.compileStatement(
                    "UPDATE LocationPoint SET band = ?, cellX = ?, cellY = ? WHERE id = ?"
                )
                db.query("SELECT id, lat, lng FROM LocationPoint").use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val lat = c.getDouble(1)
                        val lng = c.getDouble(2)
                        val band = bandOf(lat)
                        val cx = cellXOf(lat)
                        val cy = cellYOf(lng, band)
                        update.clearBindings()
                        update.bindLong(1, band.toLong())
                        update.bindLong(2, cx.toLong())
                        update.bindLong(3, cy.toLong())
                        update.bindLong(4, id)
                        update.executeUpdateDelete()
                    }
                }
                db.execSQL(
                    """
                    DELETE FROM LocationPoint
                    WHERE id NOT IN (
                        SELECT MIN(id) FROM LocationPoint
                        GROUP BY sessionId, band, cellX, cellY
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_LocationPoint_sessionId_band_cellX_cellY " +
                        "ON LocationPoint(sessionId, band, cellX, cellY)"
                )
            }
        }

        // v4 → v5: adds the spatial chunk stats cache. FULLY ADDITIVE — three new tables
        // plus one CREATE INDEX on LocationPoint. No table rewrite, no LocationPoint
        // column change, so existing points are untouched (zero walk loss). The cache
        // tables start empty; StatsEngine backfills them once from existing points on the
        // next stats refresh. CREATE statements mirror Room's generated schema for the
        // ChunkCoverage / ChunkRegionCoverage / SessionStat entities so the post-migration
        // schema validates.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ChunkCoverage` (
                        `band` INTEGER NOT NULL,
                        `chunkX` INTEGER NOT NULL,
                        `chunkY` INTEGER NOT NULL,
                        `coveredCellCount` INTEGER NOT NULL,
                        `dirty` INTEGER NOT NULL,
                        PRIMARY KEY(`band`, `chunkX`, `chunkY`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ChunkRegionCoverage` (
                        `band` INTEGER NOT NULL,
                        `chunkX` INTEGER NOT NULL,
                        `chunkY` INTEGER NOT NULL,
                        `regionKey` TEXT NOT NULL,
                        `cellCount` INTEGER NOT NULL,
                        PRIMARY KEY(`band`, `chunkX`, `chunkY`, `regionKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `SessionStat` (
                        `sessionId` INTEGER NOT NULL,
                        `distanceKm` REAL NOT NULL,
                        `dirty` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `Session`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                // Deferred perf index (old TODO.md:49) — non-destructive after all.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_LocationPoint_band_cellX_cellY` " +
                        "ON `LocationPoint` (`band`, `cellX`, `cellY`)"
                )
            }
        }

        // v5 → v6: adds the per-chunk fog tile cache (ChunkFog). ADDITIVE — one new table,
        // no change to existing data. Seeds a dirty row for every chunk already known to
        // the stats cache so those tiles get recomputed (lazily, when first viewed) rather
        // than rendering as bare basemap; chunks with no points have no ChunkCoverage row
        // and stay fully fogged with no ChunkFog row needed.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ChunkFog` (
                        `band` INTEGER NOT NULL,
                        `chunkX` INTEGER NOT NULL,
                        `chunkY` INTEGER NOT NULL,
                        `clearedWkb` BLOB,
                        `dirty` INTEGER NOT NULL,
                        PRIMARY KEY(`band`, `chunkX`, `chunkY`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO ChunkFog (band, chunkX, chunkY, clearedWkb, dirty)
                    SELECT band, chunkX, chunkY, NULL, 1 FROM ChunkCoverage
                    """.trimIndent()
                )
            }
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
