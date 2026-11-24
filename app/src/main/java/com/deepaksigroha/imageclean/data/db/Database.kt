package com.deepaksigroha.imageclean.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One row per inspection or processing operation – the local modification history. */
@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val operation: String,          // INSPECT, CLEAN, EXPORT, BATCH
    val batchId: String?,
    val fileName: String,
    val format: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val hasExif: Boolean,
    val hasGps: Boolean,
    val hasXmp: Boolean,
    val hasIptc: Boolean,
    val hasC2pa: Boolean,
    val c2paState: String,
    val aiVerdict: String,
    val processedOk: Boolean,
    val verificationFailed: Boolean,
    val outputName: String?,
    val reportJson: String,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val aspectW: Int,       // 0 = keep original ratio
    val aspectH: Int,
    val maxLongEdge: Int,
    val png: Boolean,
    val quality: Int,
)

data class Stats(
    val total: Int,
    val withExif: Int,
    val withGps: Int,
    val withC2pa: Int,
    val withAi: Int,
    val processed: Int,
    val failures: Int,
)

@Dao
interface RecordDao {
    @Insert suspend fun insert(r: RecordEntity): Long
    @Query("SELECT * FROM records ORDER BY timestamp DESC") fun all(): Flow<List<RecordEntity>>
    @Query("SELECT * FROM records WHERE batchId = :batch ORDER BY id") suspend fun batch(batch: String): List<RecordEntity>
    @Query("SELECT * FROM records WHERE id = :id") suspend fun byId(id: Long): RecordEntity?
    @Query("DELETE FROM records") suspend fun clear()
    @Query(
        """SELECT
           COALESCE(SUM(CASE WHEN operation IN ('INSPECT','BATCH') THEN 1 ELSE 0 END),0) AS total,
           COALESCE(SUM(CASE WHEN operation IN ('INSPECT','BATCH') AND hasExif THEN 1 ELSE 0 END),0) AS withExif,
           COALESCE(SUM(CASE WHEN operation IN ('INSPECT','BATCH') AND hasGps THEN 1 ELSE 0 END),0) AS withGps,
           COALESCE(SUM(CASE WHEN operation IN ('INSPECT','BATCH') AND hasC2pa THEN 1 ELSE 0 END),0) AS withC2pa,
           COALESCE(SUM(CASE WHEN operation IN ('INSPECT','BATCH') AND aiVerdict IN ('AI_GENERATED','AI_ASSISTED') THEN 1 ELSE 0 END),0) AS withAi,
           COALESCE(SUM(CASE WHEN operation IN ('CLEAN','EXPORT','EDIT') AND processedOk THEN 1 ELSE 0 END),0) AS processed,
           COALESCE(SUM(CASE WHEN verificationFailed THEN 1 ELSE 0 END),0) AS failures
           FROM records"""
    )
    fun stats(): Flow<Stats>
}

@Dao
interface PresetDao {
    @Insert suspend fun insert(p: PresetEntity): Long
    @Delete suspend fun delete(p: PresetEntity)
    @Query("SELECT * FROM presets ORDER BY name") fun all(): Flow<List<PresetEntity>>
}

@Database(entities = [RecordEntity::class, PresetEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun presets(): PresetDao

    companion object {
        fun create(ctx: Context) = Room.databaseBuilder(ctx, AppDatabase::class.java, "imageclean.db").build()
    }
}
