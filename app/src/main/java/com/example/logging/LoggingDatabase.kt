package com.example.logging

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- ENTITIES ---

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTimeMillis: Long, // Wall clock anchor
    val startMonotonicMs: Long, // Monotonic time reference
    val endTimeMonotonicMs: Long? = null,
    val weatherEnrichment: String? = null
)

@Entity(tableName = "state_intervals")
data class StateIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val stateName: String,
    val startTimeMonotonicMs: Long,
    val endTimeMonotonicMs: Long? = null,
    val startBrightness: Float,
    val endBrightness: Float
)

@Entity(tableName = "wled_intervals")
data class WledIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val stateName: String,
    val startTimeMonotonicMs: Long,
    val endTimeMonotonicMs: Long? = null,
    val lookJson: String
)

@Entity(tableName = "imu_samples")
data class ImuSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val timestampMonotonicMs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val fusedAcceleration: Float,
    val calibratedAxis: String,
    val calibratedSign: Int
)

@Entity(tableName = "bosch_samples")
data class BoschSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val timestampMonotonicMs: Long,
    val speed: Float,
    val batterySoc: Int?,
    val assistMode: String?
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
}

@Dao
interface WledIntervalDao {
    @Insert
    suspend fun insertInterval(interval: WledIntervalEntity): Long

    @Update
    suspend fun updateInterval(interval: WledIntervalEntity)

    @Query("SELECT * FROM wled_intervals WHERE sessionId = :sessionId ORDER BY startTimeMonotonicMs ASC")
    fun getIntervalsForSession(sessionId: Long): Flow<List<WledIntervalEntity>>
}

@Dao
interface ImuSampleDao {
    @Insert
    suspend fun insertSample(sample: ImuSampleEntity)

    @Insert
    suspend fun insertSamples(samples: List<ImuSampleEntity>)

    @Query("SELECT * FROM imu_samples WHERE sessionId = :sessionId ORDER BY timestampMonotonicMs ASC")
    fun getSamplesForSession(sessionId: Long): Flow<List<ImuSampleEntity>>

    @Query("DELETE FROM imu_samples WHERE sessionId = :sessionId")
    suspend fun deleteSamplesForSession(sessionId: Long)
}

@Dao
interface BoschSampleDao {
    @Insert
    suspend fun insertSample(sample: BoschSampleEntity)

    @Query("SELECT * FROM bosch_samples WHERE sessionId = :sessionId ORDER BY timestampMonotonicMs ASC")
    fun getSamplesForSession(sessionId: Long): Flow<List<BoschSampleEntity>>
}

// --- DATABASE ---

@Database(
    entities = [
        SessionEntity::class,
        StateIntervalEntity::class,
        WledIntervalEntity::class,
        ImuSampleEntity::class,
        BoschSampleEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LoggingDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun stateIntervalDao(): StateIntervalDao
    abstract fun wledIntervalDao(): WledIntervalDao
    abstract fun imuSampleDao(): ImuSampleDao
    abstract fun boschSampleDao(): BoschSampleDao

    companion object {
        @Volatile
        private var INSTANCE: LoggingDatabase? = null

        fun getDatabase(context: Context): LoggingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LoggingDatabase::class.java,
                    "prettynights_logging_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- REPOSITORY ---

class LogRepository(private val db: LoggingDatabase) {
    val allSessions: Flow<List<SessionEntity>> = db.sessionDao().getAllSessions()

    suspend fun startSession(startTimeMillis: Long, startMonotonicMs: Long, weather: String? = null): Long {
        val session = SessionEntity(
            startTimeMillis = startTimeMillis,
            startMonotonicMs = startMonotonicMs,
            weatherEnrichment = weather
        )
        return db.sessionDao().insertSession(session)
    }

    suspend fun endSession(sessionId: Long, endMonotonicMs: Long) {
        val session = db.sessionDao().getSessionById(sessionId)
        if (session != null) {
            db.sessionDao().updateSession(session.copy(endTimeMonotonicMs = endMonotonicMs))
        }
    }

    suspend fun deleteSession(sessionId: Long) {
        db.sessionDao().deleteSessionById(sessionId)
    }

    suspend fun insertStateInterval(interval: StateIntervalEntity): Long {
        return db.stateIntervalDao().insertInterval(interval)
    }

    suspend fun updateStateInterval(interval: StateIntervalEntity) {
        db.stateIntervalDao().updateInterval(interval)
    }

    suspend fun insertWledInterval(interval: WledIntervalEntity): Long {
        return db.wledIntervalDao().insertInterval(interval)
    }

    suspend fun updateWledInterval(interval: WledIntervalEntity) {
        db.wledIntervalDao().updateInterval(interval)
    }

    suspend fun insertImuSample(sample: ImuSampleEntity) {
        db.imuSampleDao().insertSample(sample)
    }

    suspend fun insertImuSamples(samples: List<ImuSampleEntity>) {
        if (samples.isNotEmpty()) {
            db.imuSampleDao().insertSamples(samples)
        }
    }

    suspend fun insertBoschSample(sample: BoschSampleEntity) {
        db.boschSampleDao().insertSample(sample)
    }

    fun getStateIntervals(sessionId: Long): Flow<List<StateIntervalEntity>> =
        db.stateIntervalDao().getIntervalsForSession(sessionId)

    fun getWledIntervals(sessionId: Long): Flow<List<WledIntervalEntity>> =
        db.wledIntervalDao().getIntervalsForSession(sessionId)

    fun getImuSamples(sessionId: Long): Flow<List<ImuSampleEntity>> =
        db.imuSampleDao().getSamplesForSession(sessionId)

    fun getBoschSamples(sessionId: Long): Flow<List<BoschSampleEntity>> =
        db.boschSampleDao().getSamplesForSession(sessionId)
}
