package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.DetectedMedia
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectedMediaDao {
    @Query("SELECT * FROM detected_media ORDER BY detectedAtMillis DESC LIMIT 100")
    fun getAllDetectedMedia(): Flow<List<DetectedMedia>>

    @Query("SELECT * FROM detected_media WHERE mediaStoreId = :mediaStoreId LIMIT 1")
    suspend fun getByMediaStoreId(mediaStoreId: Long): DetectedMedia?

    @Query("SELECT COUNT(*) FROM detected_media")
    fun getDetectedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: DetectedMedia): Long

    @Query("DELETE FROM detected_media")
    suspend fun clearAll()
}
