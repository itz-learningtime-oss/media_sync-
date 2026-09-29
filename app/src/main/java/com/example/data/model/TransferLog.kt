package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TransferProtocol {
    HTTP,
    FTP
}

enum class TransferStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED
}

@Entity(tableName = "transfer_logs")
data class TransferLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val fileUriString: String,
    val protocol: TransferProtocol,
    val targetIp: String,
    val targetPort: Int,
    val status: TransferStatus,
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val errorMessage: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)
