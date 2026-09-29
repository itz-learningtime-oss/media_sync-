package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "detected_media")
data class DetectedMedia(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mediaStoreId: Long,
    val contentUriString: String,
    val displayName: String,
    val filePath: String?,
    val sizeBytes: Long,
    val mimeType: String?,
    val dateAddedSeconds: Long,
    val isScreenshot: Boolean,
    val bucketName: String?,
    val detectedAtMillis: Long = System.currentTimeMillis()
) {
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var size = sizeBytes.toDouble()
            var unitIndex = 0
            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }
            return "%.1f %s".format(size, units[unitIndex])
        }
}
