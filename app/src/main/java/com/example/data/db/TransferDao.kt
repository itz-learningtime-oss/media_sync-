package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.TransferLog
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_logs ORDER BY timestampMillis DESC LIMIT 100")
    fun getAllTransfers(): Flow<List<TransferLog>>

    @Query("SELECT COUNT(*) FROM transfer_logs WHERE status = 'SUCCESS'")
    fun getSuccessCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transfer_logs WHERE protocol = 'HTTP' AND status = 'SUCCESS'")
    fun getHttpSuccessCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transfer_logs WHERE protocol = 'FTP' AND status = 'SUCCESS'")
    fun getFtpSuccessCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: TransferLog): Long

    @Update
    suspend fun update(log: TransferLog)

    @Query("DELETE FROM transfer_logs")
    suspend fun clearHistory()
}
