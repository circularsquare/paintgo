package com.anita.paintgo.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity
data class Owner(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val isSelf: Boolean,
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Owner::class,
            parentColumns = ["id"],
            childColumns = ["ownerId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("ownerId")],
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerId: Long,
    val startTime: Long,
    val endTime: Long? = null,
    val name: String? = null,
    val importedAt: Long? = null,
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("sessionId"),
        // Dedup within a session at ~5m resolution. Stationary fixes get squashed;
        // revisits in later sessions still record (intentional — useful for stats).
        // band is part of the key because cellY's meters-per-unit depends on the band's
        // representative latitude; a point at lat 49.9 and a point at lat 50.1 are in
        // different cells even if their lng matches.
        Index(value = ["sessionId", "band", "cellX", "cellY"], unique = true),
    ],
)
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val accuracy: Float,
    val band: Int,
    val cellX: Int,
    val cellY: Int,
)
