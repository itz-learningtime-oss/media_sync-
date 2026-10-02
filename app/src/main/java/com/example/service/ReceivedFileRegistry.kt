package com.example.service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class ReceivedFileItem(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val sizeBytes: Long,
    val uri: Uri?,
    val localPath: String? = null,
    val mimeType: String = "*/*",
    val receivedAtMillis: Long = System.currentTimeMillis()
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

    val isImage: Boolean
        get() = mimeType.startsWith("image/", ignoreCase = true) ||
                fileName.endsWith(".jpg", ignoreCase = true) ||
                fileName.endsWith(".jpeg", ignoreCase = true) ||
                fileName.endsWith(".png", ignoreCase = true) ||
                fileName.endsWith(".webp", ignoreCase = true)

    val isVideo: Boolean
        get() = mimeType.startsWith("video/", ignoreCase = true) ||
                fileName.endsWith(".mp4", ignoreCase = true) ||
                fileName.endsWith(".mkv", ignoreCase = true)
}

object ReceivedFileRegistry {

    private const val TAG = "ReceivedFileRegistry"

    private val _receivedFiles = MutableStateFlow<List<ReceivedFileItem>>(emptyList())
    val receivedFiles: StateFlow<List<ReceivedFileItem>> = _receivedFiles.asStateFlow()

    fun addReceivedFile(item: ReceivedFileItem) {
        val current = _receivedFiles.value.toMutableList()
        current.add(0, item)
        _receivedFiles.value = current
        Log.i(TAG, "Registered received file: ${item.fileName} (${item.formattedSize})")
    }

    fun removeReceivedFile(id: String) {
        _receivedFiles.value = _receivedFiles.value.filter { it.id != id }
    }

    fun clearAll() {
        _receivedFiles.value = emptyList()
    }

    /**
     * Saves a received image or video directly to the device's public Gallery
     * (DCIM / Pictures) and triggers the media scanner.
     */
    fun saveToGallery(context: Context, item: ReceivedFileItem): Boolean {
        try {
            val resolver = context.contentResolver
            val isVideo = item.isVideo
            val targetUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val subFolder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$subFolder/MediaSync")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val galleryUri = resolver.insert(targetUri, values) ?: return false

            item.uri?.let { sourceUri ->
                resolver.openInputStream(sourceUri)?.use { input ->
                    resolver.openOutputStream(galleryUri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(galleryUri, values, null, null)
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(galleryUri.path ?: ""),
                    arrayOf(mimeType),
                    null
                )
            }

            Log.i(TAG, "Successfully saved ${item.fileName} to Gallery: $galleryUri")
            Toast.makeText(context, "Saved to Gallery: ${item.fileName}", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to gallery", e)
            Toast.makeText(context, "Failed to save to Gallery: ${e.message}", Toast.LENGTH_LONG).show()
            return false
        }
    }
}
