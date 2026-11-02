package com.example.logging

import android.content.Context
import androidx.room.*
import com.example.config.RideMarkerType
import com.example.config.SpeedSourceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// --- TYPE CONVERTERS ---

class EnumConverters {
    @TypeConverter
    fun speedSourceToStr(v: SpeedSourceMode): String = v.wireValue

    @TypeConverter
    fun strToSpeedSource(s: String): SpeedSourceMode = SpeedSourceMode.fromWireValue(s)

    @TypeConverter
    fun markerToStr(v: RideMarkerType?): String? = v?.wireValue

    @TypeConverter
    fun strToMarker(s: String?): RideMarkerType? = RideMarkerType.fromWireValueOrNull(s)
}

// --- ENTITIES ---

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTimeMillis: Long, // Wall clock anchor
    val startMonotonicMs: Long, // Monotonic time reference
    val endTimeMonotonicMs: Long? = null,
    val weatherEnrichment: String? = null,
    val title: String = "",
    val notes: String = "",
    val logOnlyMode: Boolean = false,
    val configuredSpeedSource: SpeedSourceMode = SpeedSourceMode.GPS_ONLY
)

@Entity(
    tableName = "state_intervals",
    indices = [Index(value = ["sessionId", "startTimeMonotonicMs"])]
)
data class StateIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val stateName: String,
    val startTimeMonotonicMs: Long,
    val endTimeMonotonicMs: Long? = null,
    val startBrightness: Float,
    val endBrightness: Float
)

@Entity(
    tableName = "wled_intervals",
    indices = [Index(value = ["sessionId", "startTimeMonotonicMs"])]
)
data class WledIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val stateName: String,
    val startTimeMonotonicMs: Long,
    val endTimeMonotonicMs: Long? = null,
    val lookJson: String
)

/**
 * 50 Hz unified ride telemetry sample recorded on every ride (both WLED-enabled and Log-Only).
 */
@Entity(
    tableName = "imu_samples",
    indices = [Index(value = ["sessionId", "timestampMonotonicMs"])]
)
data class ImuSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val timestampMonotonicMs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val fusedAcceleration: Float,
    val calibratedAxis: String,
    val calibratedSign: Int,
    val speedKmh: Float = 0f,
    val gpsSpeedKmh: Float = 0f,
    val boschSpeedKmh: Float? = null,
    val boschAgeMs: Long = -1L,
    val boschBikeNotDriving: Boolean? = null,
    val boschCadenceRpm: Int? = null,
    val boschRiderPowerW: Int? = null,
    val boschAmbientLux: Float? = null,
    val boschBatterySoc: Int? = null,
    val boschBikeLight: Int? = null,
    val activeSpeedSource: String = "GPS",
    val stateName: String = "IDLE"
)

/**
 * Raw Bosch eBike Systems Live Data Interface V1.0 (`com.bosch.ebike.LiveData`) notification packet
 * received on characteristic `0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4`.
 */
@Entity(
    tableName = "bosch_samples",
    indices = [Index(value = ["sessionId", "timestampMonotonicMs"])]
)
data class BoschSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val timestampMonotonicMs: Long,
    val negotiatedMtu: Int = 247,
    val payloadSizeBytes: Int = 0,
    val decodeSuccess: Boolean = true,
    val presentFieldsMask: Int = 0,
    val speedHundredthsKmh: Int? = null,      // Field 1: 1/100 km/h
    val speedKmh: Float? = null,              // Field 1 converted to km/h
    val cadenceRpm: Int? = null,              // Field 2: rpm
    val riderPowerWatts: Int? = null,         // Field 5: W
    val ambientMilliLux: Long? = null,        // Field 9: 1/1000 lux
    val ambientLux: Float? = null,            // Field 9 converted to lux
    val batterySoc: Int? = null,              // Field 10: %
    val ebikeTimeUtcSec: Long? = null,        // Field 11: UTC seconds
    val odometerMeters: Long? = null,         // Field 12: meters
    val bikeLightRaw: Int? = null,            // Field 17: LightState enum
    val systemLocked: Boolean? = null,        // Field 21
    val chargerConnected: Boolean? = null,    // Field 22
    val lightReserveState: Boolean? = null,   // Field 23
    val diagnosisActive: Boolean? = null,     // Field 24
    val bikeNotDriving: Boolean? = null,      // Field 25
    val gpsSpeedKmhAtCapture: Float = 0f      // Simultaneous GPS speed for delta analysis
)

/**
 * Live in-ride event marker stamped by the rider using RideMarkerType.
 */
@Entity(
    tableName = "ride_markers",
    indices = [Index(value = ["sessionId", "timestampMonotonicMs"])]
)
data class RideMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val timestampMonotonicMs: Long,
    val markerType: RideMarkerType?,
    val note: String = ""
)

// --- DAOS ---

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startTimeMillis DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSessionById(id: Long)
}

@Dao
interface StateIntervalDao {
    @Insert
    suspend fun insertInterval(interval: StateIntervalEntity): Long

    @Update
    suspend fun updateInterval(interval: StateIntervalEntity)

    @Query("SELECT * FROM state_intervals WHERE sessionId = :sessionId ORDER BY startTimeMonotonicMs ASC")
    fun getIntervalsForSession(sessionId: Long): Flow<List<StateIntervalEntity>>

    @Query("DELETE FROM state_intervals WHERE sessionId = :sessionId")
    suspend fun deleteIntervalsForSession(sessionId: Long)
}

@Dao
interface WledIntervalDao {
    @Insert
    suspend fun insertInterval(interval: WledIntervalEntity): Long

    @Update
    suspend fun updateInterval(interval: WledIntervalEntity)

    @Query("SELECT * FROM wled_intervals WHERE sessionId = :sessionId ORDER BY startTimeMonotonicMs ASC")
    fun getIntervalsForSession(sessionId: Long): Flow<List<WledIntervalEntity>>

    @Query("DELETE FROM wled_intervals WHERE sessionId = :sessionId")
    suspend fun deleteIntervalsForSession(sessionId: Long)
}

data class ImuSessionSummary(
    val totalSamples: Int = 0,
    val freshBoschSamples: Int = 0,
    val maxBoschSpeedKmh: Float? = null
)

@Dao
interface ImuSampleDao {
    @Insert
    suspend fun insertSample(sample: ImuSampleEntity)

    @Insert
    suspend fun insertSamples(samples: List<ImuSampleEntity>)

    @Query("SELECT * FROM imu_samples WHERE sessionId = :sessionId ORDER BY timestampMonotonicMs ASC")
    fun getSamplesForSession(sessionId: Long): Flow<List<ImuSampleEntity>>

    @Query(
        """
        SELECT 
            COUNT(*) AS totalSamples,
            COALESCE(SUM(CASE WHEN boschAgeMs BETWEEN 0 AND 3999 THEN 1 ELSE 0 END), 0) AS freshBoschSamples,
            MAX(boschSpeedKmh) AS maxBoschSpeedKmh
        FROM imu_samples
        WHERE sessionId = :sessionId
        """
    )
    suspend fun getSessionSummary(sessionId: Long): ImuSessionSummary

    @Query("DELETE FROM imu_samples WHERE sessionId = :sessionId")
    suspend fun deleteSamplesForSession(sessionId: Long)
}

@Dao
interface BoschSampleDao {
    @Insert
    suspend fun insertSample(sample: BoschSampleEntity)

    @Insert
    suspend fun insertSamples(samples: List<BoschSampleEntity>)

    @Query("SELECT * FROM bosch_samples WHERE sessionId = :sessionId ORDER BY timestampMonotonicMs ASC")
    fun getSamplesForSession(sessionId: Long): Flow<List<BoschSampleEntity>>

    @Query("DELETE FROM bosch_samples WHERE sessionId = :sessionId")
    suspend fun deleteSamplesForSession(sessionId: Long)
}

@Dao
interface RideMarkerDao {
    @Insert
    suspend fun insertMarker(marker: RideMarkerEntity): Long

    @Query("SELECT * FROM ride_markers WHERE sessionId = :sessionId ORDER BY timestampMonotonicMs ASC")
    fun getMarkersForSession(sessionId: Long): Flow<List<RideMarkerEntity>>

    @Query("DELETE FROM ride_markers WHERE sessionId = :sessionId")
    suspend fun deleteMarkersForSession(sessionId: Long)
}

// --- DATABASE ---

@Database(
    entities = [
        SessionEntity::class,
        StateIntervalEntity::class,
        WledIntervalEntity::class,
        ImuSampleEntity::class,
        BoschSampleEntity::class,
        RideMarkerEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(EnumConverters::class)
abstract class LoggingDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun stateIntervalDao(): StateIntervalDao
    abstract fun wledIntervalDao(): WledIntervalDao
    abstract fun imuSampleDao(): ImuSampleDao
    abstract fun boschSampleDao(): BoschSampleDao
    abstract fun rideMarkerDao(): RideMarkerDao

    companion object {
        @Volatile
        private var INSTANCE: LoggingDatabase? = null

        fun getDatabase(context: Context): LoggingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LoggingDatabase::class.java,
                    "prettynights_logging_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- REPOSITORY ---

open class LogRepository(private val db: LoggingDatabase? = null) {
    open val allSessions: Flow<List<SessionEntity>> = db?.sessionDao()?.getAllSessions() ?: emptyFlow()

    open suspend fun startSession(
        startTimeMillis: Long,
        startMonotonicMs: Long,
        weather: String? = null,
        logOnlyMode: Boolean = false,
        configuredSpeedSource: SpeedSourceMode = SpeedSourceMode.GPS_ONLY
    ): Long {
        val session = SessionEntity(
            startTimeMillis = startTimeMillis,
            startMonotonicMs = startMonotonicMs,
            weatherEnrichment = weather,
            logOnlyMode = logOnlyMode,
            configuredSpeedSource = configuredSpeedSource
        )
        return db?.sessionDao()?.insertSession(session) ?: 0L
    }

    open suspend fun endSession(sessionId: Long, endMonotonicMs: Long) {
        val dao = db?.sessionDao() ?: return
        val session = dao.getSessionById(sessionId)
        if (session != null) {
            dao.updateSession(session.copy(endTimeMonotonicMs = endMonotonicMs))
        }
    }

    open suspend fun updateSessionMetadata(sessionId: Long, title: String, notes: String) {
        val dao = db?.sessionDao() ?: return
        val session = dao.getSessionById(sessionId)
        if (session != null) {
            dao.updateSession(session.copy(title = title.trim(), notes = notes.trim()))
        }
    }

    open suspend fun deleteSession(sessionId: Long) {
        val database = db ?: return
        database.imuSampleDao().deleteSamplesForSession(sessionId)
        database.boschSampleDao().deleteSamplesForSession(sessionId)
        database.stateIntervalDao().deleteIntervalsForSession(sessionId)
        database.wledIntervalDao().deleteIntervalsForSession(sessionId)
        database.rideMarkerDao().deleteMarkersForSession(sessionId)
        database.sessionDao().deleteSessionById(sessionId)
    }

    open suspend fun insertStateInterval(interval: StateIntervalEntity): Long {
        return db?.stateIntervalDao()?.insertInterval(interval) ?: 0L
    }

    open suspend fun updateStateInterval(interval: StateIntervalEntity) {
        db?.stateIntervalDao()?.updateInterval(interval)
    }

    open suspend fun insertWledInterval(interval: WledIntervalEntity): Long {
        return db?.wledIntervalDao()?.insertInterval(interval) ?: 0L
    }

    open suspend fun updateWledInterval(interval: WledIntervalEntity) {
        db?.wledIntervalDao()?.updateInterval(interval)
    }

    open suspend fun insertImuSample(sample: ImuSampleEntity) {
        db?.imuSampleDao()?.insertSample(sample)
    }

    open suspend fun insertImuSamples(samples: List<ImuSampleEntity>) {
        if (samples.isNotEmpty()) {
            db?.imuSampleDao()?.insertSamples(samples)
        }
    }

    open suspend fun insertBoschSample(sample: BoschSampleEntity) {
        db?.boschSampleDao()?.insertSample(sample)
    }

    open suspend fun insertRideMarker(marker: RideMarkerEntity): Long {
        return db?.rideMarkerDao()?.insertMarker(marker) ?: 0L
    }

    open fun getStateIntervals(sessionId: Long): Flow<List<StateIntervalEntity>> =
        db?.stateIntervalDao()?.getIntervalsForSession(sessionId) ?: emptyFlow()

    open fun getWledIntervals(sessionId: Long): Flow<List<WledIntervalEntity>> =
        db?.wledIntervalDao()?.getIntervalsForSession(sessionId) ?: emptyFlow()

    open fun getImuSamples(sessionId: Long): Flow<List<ImuSampleEntity>> =
        db?.imuSampleDao()?.getSamplesForSession(sessionId) ?: emptyFlow()

    open suspend fun getImuSessionSummary(sessionId: Long): ImuSessionSummary =
        db?.imuSampleDao()?.getSessionSummary(sessionId) ?: ImuSessionSummary()

    open fun getBoschSamples(sessionId: Long): Flow<List<BoschSampleEntity>> =
        db?.boschSampleDao()?.getSamplesForSession(sessionId) ?: emptyFlow()

    open fun getRideMarkers(sessionId: Long): Flow<List<RideMarkerEntity>> =
        db?.rideMarkerDao()?.getMarkersForSession(sessionId) ?: emptyFlow()
}
