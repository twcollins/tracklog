package com.tomcollins.tracklog.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class ActivityType(val label: String, /** Faster than this between fixes is treated as a GPS glitch. */ val maxPlausibleMps: Double) {
    WALK("Walk", 7.0), HIKE("Hike", 7.0), RUN("Run", 12.0), CYCLE("Cycle", 30.0), PADDLE("Paddle", 10.0),
    SKI("Ski", 45.0), DRIVE("Drive", 80.0), OTHER("Other", 80.0);

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val activity: String = ActivityType.WALK.name,
    val startTime: Long,
    val endTime: Long = startTime,
    val distanceM: Double = 0.0,
    /** Elapsed recording time, excluding pauses. */
    val totalTimeMs: Long = 0,
    val movingTimeMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val minAltM: Double? = null,
    val maxAltM: Double? = null,
    val pointCount: Int = 0,
    val recording: Boolean = false,
) {
    val activityType: ActivityType get() = ActivityType.of(activity)
}

@Entity(tableName = "points", indices = [Index("trackId")])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val accuracy: Float?,
    val speed: Float?,
    /** Increments on each pause/resume so gaps aren't drawn or counted. */
    val segment: Int,
)

@Entity(tableName = "markers", indices = [Index("trackId")])
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val name: String,
    val note: String = "",
    /** Absolute path of a JPEG inside the app's private files/photos directory. */
    val photoPath: String? = null,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY startTime DESC")
    fun tracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun track(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrack(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE recording = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun unfinishedTrack(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE recording = 1 ORDER BY startTime DESC LIMIT 1")
    fun unfinishedTrackFlow(): Flow<TrackEntity?>

    @Insert
    suspend fun insertTrack(t: TrackEntity): Long

    @Update
    suspend fun updateTrack(t: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackRow(id: Long)

    @Insert
    suspend fun insertPoint(p: PointEntity): Long

    @Insert
    suspend fun insertPoints(p: List<PointEntity>)

    @Query("SELECT * FROM points WHERE trackId = :trackId ORDER BY time, id")
    suspend fun points(trackId: Long): List<PointEntity>

    @Query("SELECT * FROM points WHERE trackId = :trackId ORDER BY time, id")
    fun pointsFlow(trackId: Long): Flow<List<PointEntity>>

    @Query("DELETE FROM points WHERE trackId = :trackId")
    suspend fun deletePoints(trackId: Long)

    @Query("SELECT * FROM markers WHERE trackId = :trackId ORDER BY time")
    fun markers(trackId: Long): Flow<List<MarkerEntity>>

    @Query("SELECT * FROM markers WHERE trackId = :trackId ORDER BY time")
    suspend fun getMarkers(trackId: Long): List<MarkerEntity>

    @Query("SELECT * FROM markers WHERE photoPath IS NOT NULL")
    fun allPhotoMarkers(): Flow<List<MarkerEntity>>

    @Insert
    suspend fun insertMarker(m: MarkerEntity): Long

    @Update
    suspend fun updateMarker(m: MarkerEntity)

    @Query("DELETE FROM markers WHERE id = :id")
    suspend fun deleteMarker(id: Long)

    @Query("DELETE FROM markers WHERE trackId = :trackId")
    suspend fun deleteMarkers(trackId: Long)

    @Transaction
    suspend fun deleteTrack(id: Long) {
        deleteMarkers(id)
        deletePoints(id)
        deleteTrackRow(id)
    }
}

@Database(entities = [TrackEntity::class, PointEntity::class, MarkerEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TrackDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tracklog.db").build()
    }
}
