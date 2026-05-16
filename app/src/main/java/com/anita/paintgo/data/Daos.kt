package com.anita.paintgo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OwnerDao {
    @Query("SELECT * FROM Owner WHERE isSelf = 1 LIMIT 1")
    suspend fun getSelf(): Owner?

    @Query("SELECT * FROM Owner ORDER BY id ASC")
    fun all(): Flow<List<Owner>>

    @Insert
    suspend fun insert(owner: Owner): Long
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Query("SELECT * FROM Session WHERE id = :id")
    suspend fun getById(id: Long): Session?

    @Query("SELECT * FROM Session WHERE ownerId = :ownerId ORDER BY startTime DESC")
    fun byOwner(ownerId: Long): Flow<List<Session>>

    @Query("""
        SELECT s.* FROM Session s
        JOIN Owner o ON s.ownerId = o.id
        WHERE o.isSelf = 1
        ORDER BY s.startTime ASC
    """)
    suspend fun allForSelf(): List<Session>
}

@Dao
interface LocationPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: LocationPoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<LocationPoint>)

    @Query("SELECT * FROM LocationPoint WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun bySession(sessionId: Long): List<LocationPoint>

    @Query("""
        SELECT lp.* FROM LocationPoint lp
        JOIN Session s ON lp.sessionId = s.id
        JOIN Owner o ON s.ownerId = o.id
        WHERE o.isSelf = 1
        ORDER BY lp.timestamp ASC
    """)
    fun allForSelf(): Flow<List<LocationPoint>>

    @Query("""
        SELECT lp.* FROM LocationPoint lp
        JOIN Session s ON lp.sessionId = s.id
        JOIN Owner o ON s.ownerId = o.id
        WHERE o.isSelf = 1
        ORDER BY lp.sessionId ASC, lp.timestamp ASC
    """)
    suspend fun allForSelfOnce(): List<LocationPoint>
}
